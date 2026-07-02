package tools

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"

	mcpgrafana "github.com/grafana/mcp-grafana"
)

const DefaultDrain3URL = "http://drain3.default.svc.cluster.local:8011"

type logTemplateClusterer interface {
	ClusterMessages(ctx context.Context, messages []string) ([]clusteredMessage, error)
}

type clusteredMessage struct {
	Template  string
	ClusterID string
}

type drain3TemplateClient struct {
	baseURL    string
	httpClient *http.Client
}

type drain3ClusterRequest struct {
	Messages []string `json:"messages"`
}

type drain3ClusterResponse struct {
	Results []struct {
		Template  string `json:"template"`
		ClusterID string `json:"cluster_id"`
	} `json:"results"`
}

func newDrain3TemplateClient(cfg mcpgrafana.GrafanaConfig) logTemplateClusterer {
	baseURL := strings.TrimSpace(cfg.LokiDrain3URL)
	if baseURL == "" {
		baseURL = DefaultDrain3URL
	}

	timeout := cfg.LokiDrain3Timeout
	if timeout <= 0 {
		timeout = 3 * time.Second
	}

	return &drain3TemplateClient{
		baseURL: strings.TrimRight(baseURL, "/"),
		httpClient: &http.Client{
			Timeout: timeout,
		},
	}
}

func (c *drain3TemplateClient) ClusterMessages(ctx context.Context, messages []string) ([]clusteredMessage, error) {
	payload, err := json.Marshal(drain3ClusterRequest{Messages: messages})
	if err != nil {
		return nil, fmt.Errorf("marshalling Drain3 request: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/cluster", bytes.NewReader(payload))
	if err != nil {
		return nil, fmt.Errorf("creating Drain3 request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("calling Drain3 service: %w", err)
	}
	defer func() {
		_ = resp.Body.Close()
	}()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("Drain3 service returned status code %d", resp.StatusCode)
	}

	var clusterResp drain3ClusterResponse
	if err := json.NewDecoder(resp.Body).Decode(&clusterResp); err != nil {
		return nil, fmt.Errorf("decoding Drain3 response: %w", err)
	}

	if len(clusterResp.Results) != len(messages) {
		return nil, fmt.Errorf("Drain3 service returned %d results for %d messages", len(clusterResp.Results), len(messages))
	}

	results := make([]clusteredMessage, 0, len(clusterResp.Results))
	for _, item := range clusterResp.Results {
		results = append(results, clusteredMessage{
			Template:  item.Template,
			ClusterID: item.ClusterID,
		})
	}

	return results, nil
}

func shouldTemplateCluster(level string) bool {
	switch classifyLogLevel(level) {
	case "info", "debug", "unknown":
		return true
	default:
		return false
	}
}

func applyTemplateClustering(ctx context.Context, entries []LogEntry, clusterer logTemplateClusterer) ([]LogEntry, error) {
	if len(entries) == 0 || clusterer == nil {
		return entries, nil
	}

	indexes := make([]int, 0)
	messages := make([]string, 0)
	for i := range entries {
		if entries[i].Line == nil {
			continue
		}
		if !shouldTemplateCluster(entries[i].Line.Level) {
			continue
		}
		if strings.TrimSpace(entries[i].Line.Message) == "" {
			continue
		}

		indexes = append(indexes, i)
		messages = append(messages, entries[i].Line.Message)
	}

	if len(messages) == 0 {
		return entries, nil
	}

	clustered, err := clusterer.ClusterMessages(ctx, messages)
	if err != nil {
		return nil, err
	}

	for idx, entryIndex := range indexes {
		entry := &entries[entryIndex]
		template := strings.TrimSpace(clustered[idx].Template)
		if template == "" {
			continue
		}

		entry.Line.Message = template
		entry.Line.Path = ""
		if clustered[idx].ClusterID != "" {
			if entry.Line.Extra == nil {
				entry.Line.Extra = map[string]string{}
			}
			entry.Line.Extra["cluster_id"] = clustered[idx].ClusterID
		}
	}

	return entries, nil
}
