require('dotenv').config();
const { Client, Events, GatewayIntentBits } = require('discord.js');

const { TOKEN } = process.env;

const COMMAND_CONFIG = {
  status: {
    url: 'http://localhost:5678/webhook-test/fcca9a2d-003a-49a8-9564-f53ff9da0268',
    optionNames: ['client', 'service', 'env', 'version'],
    timeoutMs: 120000,
  },
  'daily-errors': {
    url: 'http://localhost:5678/webhook/fcca9a2d-003a-49a8-9564-f53ff9da0268',
    optionNames: ['client', 'service', 'env', 'version'],
    timeoutMs: 120000,
  },
  ask: {
    url: 'http://localhost:5678/webhook/fcca9a2d-003a-49a8-9564-f53ff9da0268',
    optionNames: ['client', 'service', 'env', 'version', 'message'],
    timeoutMs: 120000,
  },
  refresh: {
    url: 'http://localhost:5678/webhook/4d23e79a-535b-4cfb-be1b-004483370910',
    optionNames: [],
    timeoutMs: 300000,
  },
  ls: {
    url: 'http://localhost:5678/webhook/f06ecae1-64de-44be-aa7c-02ddfb203992',
    optionNames: ['uid', 'label'],
    timeoutMs: 120000,
  },
};

if (!TOKEN) {
  throw new Error('Missing TOKEN in .env');
}

function buildPreview(text, maxLength = 1900) {
  const normalized = typeof text === 'string' ? text.trim() : '';

  if (!normalized) {
    return 'No preview available.';
  }

  return normalized.length > maxLength
    ? `${normalized.slice(0, maxLength - 3)}...`
    : normalized;
}

function escapePdfText(value) {
  return value
    .replace(/\\/g, '\\\\')
    .replace(/\(/g, '\\(')
    .replace(/\)/g, '\\)')
    .replace(/[^\x20-\x7E]/g, '?');
}

function wrapPdfText(text, maxCharsPerLine = 90) {
  const sanitized = String(text || '').replace(/\r\n/g, '\n');
  const lines = [];

  for (const rawLine of sanitized.split('\n')) {
    const line = rawLine.trimEnd();

    if (!line) {
      lines.push('');
      continue;
    }

    const words = line.split(/\s+/);
    let currentLine = '';

    for (const word of words) {
      const nextLine = currentLine ? `${currentLine} ${word}` : word;

      if (nextLine.length <= maxCharsPerLine) {
        currentLine = nextLine;
        continue;
      }

      if (currentLine) {
        lines.push(currentLine);
      }

      currentLine = word;
    }

    if (currentLine) {
      lines.push(currentLine);
    }
  }

  return lines.length ? lines : ['No content available.'];
}

function createPdfBuffer(text, title = 'Report') {
  const pageWidth = 595;
  const pageHeight = 842;
  const marginX = 50;
  const marginTop = 60;
  const fontSize = 11;
  const lineHeight = 14;
  const linesPerPage = Math.floor((pageHeight - marginTop * 2) / lineHeight);
  const lines = wrapPdfText(text);
  const pages = [];

  for (let i = 0; i < lines.length; i += linesPerPage) {
    pages.push(lines.slice(i, i + linesPerPage));
  }

  const objects = [];
  const pageObjectIds = [];
  let nextObjectId = 4;

  for (const pageLines of pages) {
    const streamLines = [
      'BT',
      `/F1 ${fontSize} Tf`,
      `${lineHeight} TL`,
      `${marginX} ${pageHeight - marginTop} Td`,
    ];

    for (let index = 0; index < pageLines.length; index += 1) {
      const line = escapePdfText(pageLines[index]);

      if (index > 0) {
        streamLines.push('T*');
      }

      streamLines.push(`(${line}) Tj`);
    }

    streamLines.push('ET');

    const stream = streamLines.join('\n');
    const contentObjectId = nextObjectId;
    const pageObjectId = nextObjectId + 1;
    nextObjectId += 2;

    objects[contentObjectId] =
      `${contentObjectId} 0 obj\n` +
      `<< /Length ${Buffer.byteLength(stream, 'utf8')} >>\n` +
      `stream\n${stream}\nendstream\nendobj\n`;

    objects[pageObjectId] =
      `${pageObjectId} 0 obj\n` +
      `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${pageWidth} ${pageHeight}] ` +
      `/Resources << /Font << /F1 3 0 R >> >> /Contents ${contentObjectId} 0 R >>\n` +
      `endobj\n`;

    pageObjectIds.push(pageObjectId);
  }

  objects[1] = '1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n';
  objects[2] =
    `2 0 obj\n<< /Type /Pages /Count ${pageObjectIds.length} /Kids [` +
    `${pageObjectIds.map(id => `${id} 0 R`).join(' ')}] >>\nendobj\n`;
  objects[3] =
    `3 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica ` +
    `/Encoding /WinAnsiEncoding >>\nendobj\n`;

  const pdfParts = ['%PDF-1.4\n'];
  const offsets = [0];
  let currentOffset = Buffer.byteLength(pdfParts[0], 'utf8');

  for (let objectId = 1; objectId < objects.length; objectId += 1) {
    const objectContent = objects[objectId];
    if (!objectContent) {
      continue;
    }

    offsets[objectId] = currentOffset;
    pdfParts.push(objectContent);
    currentOffset += Buffer.byteLength(objectContent, 'utf8');
  }

  const xrefOffset = currentOffset;
  const objectCount = objects.length;
  let xref = `xref\n0 ${objectCount}\n0000000000 65535 f \n`;

  for (let objectId = 1; objectId < objectCount; objectId += 1) {
    const offset = offsets[objectId] || 0;
    xref += `${String(offset).padStart(10, '0')} 00000 n \n`;
  }

  const trailer =
    `trailer\n<< /Size ${objectCount} /Root 1 0 R >>\n` +
    `startxref\n${xrefOffset}\n%%EOF`;

  pdfParts.push(xref, trailer);

  return Buffer.from(pdfParts.join(''), 'utf8');
}

async function sendSimpleReply(interaction, message) {
  await interaction.editReply({
    content: buildPreview(message || 'No response returned.'),
    files: [],
  });
}

async function sendRawReply(interaction, message) {
  const rawMessage =
    typeof message === 'string' && message.length > 0
      ? message
      : 'No response returned.';

  if (rawMessage.length <= 2000) {
    await interaction.editReply({
      content: rawMessage,
      files: [],
    });
    return;
  }

  await interaction.editReply({
    content: 'Response exceeded Discord message limits, attached as-is.',
    files: [
      {
        attachment: Buffer.from(rawMessage, 'utf8'),
        name: `${interaction.commandName}-response.txt`,
      },
    ],
  });
}

async function sendFileReply(interaction, fileBuffer, fileName, message, previewText) {
  const preview = buildPreview(previewText);
  const content = message || `Report generated: \`${fileName}\`\n\n${preview}`;

  await interaction.editReply({
    content,
    files: [
      {
        attachment: fileBuffer,
        name: fileName,
      },
    ],
  });
}

const client = new Client({
  intents: [GatewayIntentBits.Guilds],
});

client.once(Events.ClientReady, readyClient => {
  console.log(`Logged in as ${readyClient.user.tag}`);
});

client.on(Events.InteractionCreate, async interaction => {
  if (!interaction.isChatInputCommand()) return;

  const commandConfig = COMMAND_CONFIG[interaction.commandName];
  if (!commandConfig) return;

  await interaction.deferReply();

  const payload = {
    commandName: interaction.commandName,
    userId: interaction.user.id,
    username: interaction.user.tag,
    guildId: interaction.guildId,
    channelId: interaction.channelId,
  };

  // Add options dynamically
  for (const optionName of commandConfig.optionNames) {
    const value = interaction.options.getString(optionName);
    if (value !== null) {
      payload[optionName] = value;
    }
  }

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), commandConfig.timeoutMs);

  try {
    const response = await fetch(commandConfig.url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload),
      signal: controller.signal,
    });

    clearTimeout(timeout);

    const contentType = response.headers.get('content-type') || '';

    if (interaction.commandName === 'ls') {
      if (contentType.includes('application/json') || contentType.startsWith('text/')) {
        const rawBody = await response.text();
        await sendRawReply(interaction, rawBody);
        return;
      }

      const buffer = Buffer.from(await response.arrayBuffer());
      const fileName = response.headers.get('x-file-name') || 'ls-response.bin';

      await interaction.editReply({
        content: '',
        files: [
          {
            attachment: buffer,
            name: fileName,
          },
        ],
      });
      return;
    }

    if (contentType.includes('application/json')) {
      const data = await response.json();

      if (interaction.commandName === 'refresh') {
        const responseText =
          data?.message ?? data?.output ?? data?.text ?? JSON.stringify(data, null, 2);
        await sendSimpleReply(interaction, responseText);
        return;
      }

      const base64File =
        data?.fileBase64 ||
        data?.binary?.file?.data ||
        data?.data;

      if (base64File) {
        const fileName =
          data?.fileName ||
          data?.binary?.file?.fileName ||
          'report.txt';
        const originalBuffer = Buffer.from(base64File, 'base64');
        const isPdfFile =
          fileName.toLowerCase().endsWith('.pdf') ||
          originalBuffer.subarray(0, 4).toString('utf8') === '%PDF';
        const previewText = data?.preview || data?.output || data?.text || '';
        const message = data?.message;
        const fileBuffer = isPdfFile
          ? originalBuffer
          : createPdfBuffer(previewText || 'See attached report output.', fileName);

        await sendFileReply(
          interaction,
          fileBuffer,
          fileName,
          message,
          previewText
        );
        return;
      }

      const responseText = buildPreview(
        data?.message || data?.output || data?.text || JSON.stringify(data, null, 2)
      );

      const pdfText = response.ok
        ? responseText
        : `Webhook request failed.\n\n${responseText}`;

      await sendFileReply(
        interaction,
        createPdfBuffer(pdfText, `${interaction.commandName} response`),
        'report.pdf',
        null,
        pdfText
      );
      return;
    }

    if (contentType.includes('text/plain')) {
      const rawBody = await response.text();

      if (interaction.commandName === 'refresh') {
        await sendSimpleReply(interaction, rawBody);
        return;
      }

      await sendFileReply(
        interaction,
        createPdfBuffer(rawBody, `${interaction.commandName} response`),
        'report.pdf',
        null,
        rawBody
      );
      return;
    }

    const buffer = Buffer.from(await response.arrayBuffer());
    const fileName = response.headers.get('x-file-name') || 'report.pdf';
    const isPdfFile =
      fileName.toLowerCase().endsWith('.pdf') ||
      buffer.subarray(0, 4).toString('utf8') === '%PDF';

    if (interaction.commandName === 'refresh') {
      await sendSimpleReply(interaction, buffer.toString('utf8').trim());
      return;
    }

    await sendFileReply(
      interaction,
      isPdfFile ? buffer : createPdfBuffer('See attached file.', fileName),
      fileName,
      null,
      'See attached file.'
    );

  } catch (error) {
    clearTimeout(timeout);

    const message =
      error.name === 'AbortError'
        ? `Webhook request timed out after ${Math.floor(commandConfig.timeoutMs / 1000)} seconds.`
        : `Webhook request failed: ${error.message}`;

    await interaction.editReply({ content: message });
  }
});

client.login(TOKEN);
