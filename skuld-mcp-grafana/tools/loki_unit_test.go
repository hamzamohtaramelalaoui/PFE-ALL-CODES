package tools

import (
	"context"
	"encoding/json"
	"errors"
	"testing"

	mcpgrafana "github.com/grafana/mcp-grafana"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type stubLogTemplateClusterer struct {
	results []clusteredMessage
	err     error
}

func (s stubLogTemplateClusterer) ClusterMessages(_ context.Context, _ []string) ([]clusteredMessage, error) {
	if s.err != nil {
		return nil, s.err
	}
	return s.results, nil
}

func TestEnforceLogLimit(t *testing.T) {
	tests := []struct {
		name           string
		maxLokiLimit   int
		requestedLimit int
		expectedLimit  int
	}{
		{
			name:           "default limit when requested is 0",
			maxLokiLimit:   100,
			requestedLimit: 0,
			expectedLimit:  DefaultLokiLogLimit,
		},
		{
			name:           "default limit when requested is negative",
			maxLokiLimit:   100,
			requestedLimit: -5,
			expectedLimit:  DefaultLokiLogLimit,
		},
		{
			name:           "requested limit within bounds",
			maxLokiLimit:   100,
			requestedLimit: 50,
			expectedLimit:  50,
		},
		{
			name:           "requested limit exceeds max",
			maxLokiLimit:   100,
			requestedLimit: 150,
			expectedLimit:  100,
		},
		{
			name:           "custom max limit from config",
			maxLokiLimit:   500,
			requestedLimit: 300,
			expectedLimit:  300,
		},
		{
			name:           "requested limit exceeds custom max",
			maxLokiLimit:   500,
			requestedLimit: 600,
			expectedLimit:  500,
		},
		{
			name:           "fallback to default max when config is 0",
			maxLokiLimit:   0,
			requestedLimit: 6000,
			expectedLimit:  MaxLokiLogLimit, // 5000
		},
		{
			name:           "fallback to default max when config is negative",
			maxLokiLimit:   -10,
			requestedLimit: 6000,
			expectedLimit:  MaxLokiLogLimit, // 5000
		},
		{
			name:           "default limit capped to maxLimit when maxLimit is lower",
			maxLokiLimit:   5,
			requestedLimit: 0,
			expectedLimit:  5, // DefaultLokiLogLimit (10) > maxLimit (5), so use maxLimit
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			cfg := mcpgrafana.GrafanaConfig{
				MaxLokiLogLimit: tc.maxLokiLimit,
			}
			ctx := mcpgrafana.WithGrafanaConfig(context.Background(), cfg)

			result := enforceLogLimit(ctx, tc.requestedLimit)
			assert.Equal(t, tc.expectedLimit, result)
		})
	}
}

func TestHasCategorizeLabelsFlag(t *testing.T) {
	assert.True(t, hasCategorizeLabelsFlag([]string{"categorize-labels"}))
	assert.True(t, hasCategorizeLabelsFlag([]string{"other", "categorize-labels"}))
	assert.False(t, hasCategorizeLabelsFlag(nil))
	assert.False(t, hasCategorizeLabelsFlag([]string{}))
	assert.False(t, hasCategorizeLabelsFlag([]string{"other"}))
}

func TestCategorizedLabelsParsing(t *testing.T) {
	// Simulate a Loki response with categorize-labels encoding flag.
	// values[2] carries the categorized labels object.
	rawResponse := `{
		"status": "success",
		"data": {
			"resultType": "streams",
			"encodingFlags": ["categorize-labels"],
			"result": [
				{
					"stream": {"app": "frontend", "namespace": "default"},
					"values": [
						[
							"1693996529000222496",
							"level=info msg=\"request handled\"",
							{
								"structuredMetadata": {"traceID": "abc123", "service_name": "web"},
								"parsed": {"level": "info", "msg": "request handled"}
							}
						],
						[
							"1693996530000000000",
							"level=error msg=\"timeout\"",
							{
								"structuredMetadata": {"traceID": "def456"},
								"parsed": {"level": "error"}
							}
						]
					]
				}
			]
		}
	}`

	var response lokiQueryResponse
	require.NoError(t, json.Unmarshal([]byte(rawResponse), &response))

	assert.Equal(t, "streams", response.Data.ResultType)
	assert.True(t, hasCategorizeLabelsFlag(response.Data.EncodingFlags))

	// Parse streams
	var streams []LokiLogStream
	require.NoError(t, json.Unmarshal(response.Data.Result, &streams))
	require.Len(t, streams, 1)

	stream := streams[0]
	// Stream labels should only contain index labels
	assert.Equal(t, map[string]string{"app": "frontend", "namespace": "default"}, stream.Stream)
	require.Len(t, stream.Values, 2)

	// First entry — parse the third element
	require.Len(t, stream.Values[0], 3)
	var cats1 categorizedLabels
	require.NoError(t, json.Unmarshal(stream.Values[0][2], &cats1))
	assert.Equal(t, map[string]string{"traceID": "abc123", "service_name": "web"}, cats1.StructuredMetadata)
	assert.Equal(t, map[string]string{"level": "info", "msg": "request handled"}, cats1.Parsed)

	// Second entry
	var cats2 categorizedLabels
	require.NoError(t, json.Unmarshal(stream.Values[1][2], &cats2))
	assert.Equal(t, map[string]string{"traceID": "def456"}, cats2.StructuredMetadata)
	assert.Equal(t, map[string]string{"level": "error"}, cats2.Parsed)
}

func TestCategorizedLabelsBackwardCompat(t *testing.T) {
	// Without the encoding flag, values only have 2 elements (old Loki).
	rawResponse := `{
		"status": "success",
		"data": {
			"resultType": "streams",
			"result": [
				{
					"stream": {"app": "backend"},
					"values": [
						["1693996529000222496", "some log line"]
					]
				}
			]
		}
	}`

	var response lokiQueryResponse
	require.NoError(t, json.Unmarshal([]byte(rawResponse), &response))

	assert.False(t, hasCategorizeLabelsFlag(response.Data.EncodingFlags))

	var streams []LokiLogStream
	require.NoError(t, json.Unmarshal(response.Data.Result, &streams))
	require.Len(t, streams, 1)
	require.Len(t, streams[0].Values, 1)
	require.Len(t, streams[0].Values[0], 2) // Only timestamp + line, no third element
}

func TestBuildStructuredLogLine(t *testing.T) {
	t.Run("parses promtail-style error payload", func(t *testing.T) {
		rawLine := `{
			"@timestamp": "2024-06-01T12:00:00Z",
			"log": {"level": "ERROR"},
			"message": "some text",
			"error": {
				"type": "NullPointerException",
				"message": "Null pointer exception occurred in module X at line Y",
				"stack_trace": "java.lang.NullPointerException\n\tat com.marketingconfort.moduleX.methodY(File.java:123)\n\tat other.Class.main(File.java:10)"
			}
		}`

		line := buildStructuredLogLine(rawLine)
		require.NotNil(t, line)
		assert.Equal(t, "2024-06-01T12:00:00Z", line.Timestamp)
		assert.Equal(t, "error", line.Level)
		assert.Equal(t, "some text", line.Message)
		assert.Equal(t, "nullpointerexception", line.ErrorType)
		assert.Equal(t, "Null pointer exception occurred in module X at line Y", line.ErrorMessage)
		assert.Equal(t, "com.marketingconfort.moduleX.methodY(File.java:123)", line.Path)
	})

	t.Run("parses ecs dotted keys for info and warn", func(t *testing.T) {
		infoLine := `{
			"@timestamp": "2026-03-31T10:55:12.769Z",
			"log.level": "INFO",
			"message": "Sending GET request to URL: http://mc-intra-deployment-service/api/deployment/group/uuid/ee6abc14-3727-4d39-ab58-1233bf169969"
		}`
		warnLine := `{
			"@timestamp": "2026-03-31T10:41:20.382Z",
			"log.level": "WARN",
			"message": "spring.jpa.open-in-view is enabled by default"
		}`

		info := buildStructuredLogLine(infoLine)
		warn := buildStructuredLogLine(warnLine)

		require.NotNil(t, info)
		require.NotNil(t, warn)
		assert.Equal(t, "info", info.Level)
		assert.Equal(t, "warn", warn.Level)
		assert.Equal(t, "2026-03-31T10:55:12.769Z", info.Timestamp)
		assert.Equal(t, "2026-03-31T10:41:20.382Z", warn.Timestamp)
		assert.Contains(t, info.Message, "Sending GET request")
		assert.Equal(t, "/api/deployment/group/uuid/ee6abc14-3727-4d39-ab58-1233bf169969", info.Path)
		assert.Contains(t, warn.Message, "spring.jpa.open-in-view")
		assert.Empty(t, warn.Path)
	})

	t.Run("parses ecs dotted keys for error payload", func(t *testing.T) {
		rawLine := `{
			"@timestamp": "2026-03-31T10:53:55.510Z",
			"log.level": "ERROR",
			"message": "Servlet.service() for servlet [dispatcherServlet] in context with path [] threw exception",
			"error.type": "com.marketingconfort.starter.core.exceptions.FunctionalException",
			"error.message": "Can't delete properties of other groups or in envs other than Local unless you're an admin",
			"error.stack_trace": "com.marketingconfort.starter.core.exceptions.FunctionalException: boom\n\tat com.marketingconfort.mcIntraConfigserver.services.Implementation.PropertiesServiceImpl.deleteProperties(PropertiesServiceImpl.java:162)\n\tat java.base/java.lang.Thread.run(Thread.java:840)\n"
		}`

		line := buildStructuredLogLine(rawLine)
		require.NotNil(t, line)
		assert.Equal(t, "error", line.Level)
		assert.Equal(t, "com.marketingconfort.starter.core.exceptions.functionalexception", line.ErrorType)
		assert.Equal(t, "com.marketingconfort.mcIntraConfigserver.services.Implementation.PropertiesServiceImpl.deleteProperties(PropertiesServiceImpl.java:162)", line.Path)
		assert.Contains(t, line.ErrorMessage, "Can't delete properties")
	})

	t.Run("falls back to message for plain text lines", func(t *testing.T) {
		line := buildStructuredLogLine("plain text log")
		require.NotNil(t, line)
		assert.Equal(t, "plain text log", line.Message)
		assert.Empty(t, line.Level)
		assert.Empty(t, line.ErrorType)
	})
}

func TestNormalizeLogEntry(t *testing.T) {
	entry := LogEntry{
		Timestamp: "1775055266675223436",
		Line: &StructuredLogLine{
			Message: "Sending GET request to URL: http://svc/api/test",
		},
		Labels: map[string]string{
			"app":      "svc",
			"filename": "/tmp/app.log",
			"stream":   "stdout",
		},
		StructuredMetadata: map[string]string{
			"detected_level": "INFO",
			"trace_id":       "abc-123",
		},
	}

	normalizeLogEntry(&entry)

	assert.Empty(t, entry.Timestamp)
	require.NotNil(t, entry.Line)
	assert.Equal(t, "info", entry.Line.Level)
	assert.NotEmpty(t, entry.Line.Timestamp)
	assert.Equal(t, "svc", entry.Labels["app"])
	_, hasFilename := entry.Labels["filename"]
	_, hasStream := entry.Labels["stream"]
	assert.False(t, hasFilename)
	assert.False(t, hasStream)
	assert.Nil(t, entry.StructuredMetadata)
	assert.Nil(t, entry.Parsed)
	assert.Equal(t, "abc-123", entry.Line.Extra["trace_id"])
}

func TestGroupLogEntriesByLabels(t *testing.T) {
	entries := []LogEntry{
		{
			Labels: map[string]string{"app": "svc", "namespace": "mc"},
			Line:   &StructuredLogLine{Message: "first", Timestamp: "2026-04-01T14:54:26Z"},
		},
		{
			Labels: map[string]string{"namespace": "mc", "app": "svc"},
			Line:   &StructuredLogLine{Message: "second", Timestamp: "2026-04-01T14:54:27Z"},
		},
		{
			Labels: map[string]string{"app": "other"},
			Line:   &StructuredLogLine{Message: "third", Timestamp: "2026-04-01T14:54:28Z"},
		},
	}

	grouped := groupLogEntriesByLabels(entries)
	require.Len(t, grouped, 2)
	require.Len(t, grouped[0].Lines, 2)
	require.Len(t, grouped[1].Lines, 1)
	assert.Equal(t, "svc", grouped[0].Labels["app"])
	assert.Equal(t, "first", grouped[0].Lines[0].Line.Message)
	assert.Equal(t, "second", grouped[0].Lines[1].Line.Message)
	assert.Equal(t, "third", grouped[1].Lines[0].Line.Message)
}

func TestDeduplicateGroupedLines(t *testing.T) {
	groups := []QueryLokiResultItem{
		{
			Labels: map[string]string{"app": "svc"},
			Lines: []QueryLokiLine{
				{
					Line: &StructuredLogLine{
						Level:     "info",
						Message:   "same message",
						Timestamp: "2026-04-01T14:54:26.635Z",
					},
				},
				{
					Line: &StructuredLogLine{
						Level:     "info",
						Message:   "same message",
						Timestamp: "2026-04-01T14:54:26.637Z",
					},
				},
				{
					Line: &StructuredLogLine{
						Level:     "info",
						Message:   "same message",
						Timestamp: "2026-04-01T14:54:26.636Z",
					},
				},
				{
					Line: &StructuredLogLine{
						Level:        "error",
						Message:      "boom",
						Path:         "pkg.Service.method(File.java:1)",
						ErrorType:    "my.exception",
						ErrorMessage: "failed",
						Timestamp:    "2026-04-01T14:54:26.671Z",
					},
				},
				{
					Line: &StructuredLogLine{
						Level:        "error",
						Message:      "boom",
						Path:         "pkg.Service.method(File.java:1)",
						ErrorType:    "my.exception",
						ErrorMessage: "failed",
						Timestamp:    "2026-04-01T14:54:27.000Z",
					},
				},
			},
		},
	}

	deduped := deduplicateGroupedLines(groups)
	require.Len(t, deduped, 1)
	require.Len(t, deduped[0].Lines, 2)

	assert.Equal(t, 3, deduped[0].Lines[0].Line.Occurrences)
	assert.Equal(t, []string{"2026-04-01T14:54:26.635Z", "2026-04-01T14:54:26.637Z"}, deduped[0].Lines[0].Line.Timestamps)
	assert.Empty(t, deduped[0].Lines[0].Line.Timestamp)

	assert.Equal(t, 2, deduped[0].Lines[1].Line.Occurrences)
	assert.Equal(t, []string{"2026-04-01T14:54:26.671Z", "2026-04-01T14:54:27.000Z"}, deduped[0].Lines[1].Line.Timestamps)
	assert.Empty(t, deduped[0].Lines[1].Line.Timestamp)
}

func TestApplyLogLevelResponseCaps(t *testing.T) {
	makeLine := func(level, message string) QueryLokiLine {
		return QueryLokiLine{
			Line: &StructuredLogLine{
				Level:   level,
				Message: message,
			},
		}
	}

	groups := []QueryLokiResultItem{
		{
			Labels: map[string]string{"app": "svc-a"},
			Lines: []QueryLokiLine{
				makeLine("info", "info-01"),
				makeLine("info", "info-02"),
				makeLine("info", "info-03"),
				makeLine("info", "info-04"),
				makeLine("info", "info-05"),
				makeLine("info", "info-06"),
				makeLine("debug", "debug-01"),
				makeLine("debug", "debug-02"),
				makeLine("", "unknown-01"),
				makeLine("trace", "unknown-02"),
				makeLine("warn", "warn-01"),
				makeLine("error", "error-01"),
			},
		},
		{
			Labels: map[string]string{"app": "svc-b"},
			Lines: []QueryLokiLine{
				makeLine("info", "info-07"),
				makeLine("info", "info-08"),
				makeLine("info", "info-09"),
				makeLine("info", "info-10"),
				makeLine("info", "info-11"),
				makeLine("info", "info-12"),
				makeLine("debug", "debug-03"),
				makeLine("debug", "debug-04"),
				makeLine("debug", "debug-05"),
				makeLine("debug", "debug-06"),
				makeLine("", "unknown-03"),
				makeLine("", "unknown-04"),
				makeLine("", "unknown-05"),
				makeLine("", "unknown-06"),
				makeLine("warn", "warn-02"),
				makeLine("error", "error-02"),
			},
		},
	}

	filtered, capped := applyLogLevelResponseCaps(groups)
	require.True(t, capped)
	require.Len(t, filtered, 2)

	var infoCount, debugCount, unknownCount, warnCount, errorCount int
	for _, group := range filtered {
		for _, groupedLine := range group.Lines {
			switch classifyLogLevel(groupedLine.Line.Level) {
			case "info":
				infoCount++
			case "debug":
				debugCount++
			case "unknown":
				unknownCount++
			case "warn":
				warnCount++
			case "error":
				errorCount++
			}
		}
	}

	assert.Equal(t, 10, infoCount)
	assert.Equal(t, 5, debugCount)
	assert.Equal(t, 5, unknownCount)
	assert.Equal(t, 2, warnCount)
	assert.Equal(t, 2, errorCount)
	assert.Equal(t, 24, countGroupedLines(filtered))
	assert.Equal(t, "info-10", filtered[1].Lines[3].Line.Message)
}

func TestApplyTemplateClustering(t *testing.T) {
	entries := []LogEntry{
		{
			Labels: map[string]string{"app": "svc"},
			Line: &StructuredLogLine{
				Level:     "info",
				Message:   "Sending GET request to URL: http://svc-a/api/orders/123",
				Path:      "/api/orders/123",
				Timestamp: "2026-04-01T14:54:26.635Z",
			},
		},
		{
			Labels: map[string]string{"app": "svc"},
			Line: &StructuredLogLine{
				Level:     "info",
				Message:   "Sending GET request to URL: http://svc-a/api/orders/456",
				Path:      "/api/orders/456",
				Timestamp: "2026-04-01T14:54:26.637Z",
			},
		},
		{
			Labels: map[string]string{"app": "svc"},
			Line: &StructuredLogLine{
				Level:        "error",
				Message:      "boom",
				Path:         "/api/orders/456",
				ErrorType:    "timeout",
				ErrorMessage: "request failed",
				Timestamp:    "2026-04-01T14:54:27.000Z",
			},
		},
	}

	clusterer := stubLogTemplateClusterer{
		results: []clusteredMessage{
			{
				Template:  "Sending GET request to URL: http://svc-a/api/orders/<*>",
				ClusterID: "1",
			},
			{
				Template:  "Sending GET request to URL: http://svc-a/api/orders/<*>",
				ClusterID: "1",
			},
		},
	}

	clustered, err := applyTemplateClustering(context.Background(), entries, clusterer)
	require.NoError(t, err)
	require.Len(t, clustered, 3)

	assert.Equal(t, "Sending GET request to URL: http://svc-a/api/orders/<*>", clustered[0].Line.Message)
	assert.Equal(t, "Sending GET request to URL: http://svc-a/api/orders/<*>", clustered[1].Line.Message)
	assert.Empty(t, clustered[0].Line.Path)
	assert.Empty(t, clustered[1].Line.Path)
	assert.Equal(t, "1", clustered[0].Line.Extra["cluster_id"])

	grouped := deduplicateGroupedLines(groupLogEntriesByLabels(clustered))
	require.Len(t, grouped, 1)
	require.Len(t, grouped[0].Lines, 2)

	assert.Equal(t, 2, grouped[0].Lines[0].Line.Occurrences)
	assert.Equal(t, "Sending GET request to URL: http://svc-a/api/orders/<*>", grouped[0].Lines[0].Line.Message)
	assert.Equal(t, "boom", grouped[0].Lines[1].Line.Message)
	assert.Equal(t, "/api/orders/456", grouped[0].Lines[1].Line.Path)
}

func TestApplyTemplateClusteringError(t *testing.T) {
	entries := []LogEntry{
		{
			Line: &StructuredLogLine{
				Level:   "info",
				Message: "Creating RestTemplate with connectTimeout=2000 and readTimeout=4000",
			},
		},
	}

	_, err := applyTemplateClustering(context.Background(), entries, stubLogTemplateClusterer{
		err: errors.New("service unavailable"),
	})
	require.Error(t, err)
	assert.Contains(t, err.Error(), "service unavailable")
}
