const { SlashCommandBuilder } = require('discord.js');

const command = new SlashCommandBuilder()
  .setName('status')
  .setDescription('Check service status')

  .addStringOption(option =>
    option.setName('client')
      .setDescription('Client name')
      .setRequired(true)
  )

  .addStringOption(option =>
    option.setName('service')
      .setDescription('Service name')
      .setRequired(true)
  )

  .addStringOption(option =>
    option.setName('env')
      .setDescription('Environment (optional)')
      .setRequired(false)
  )

  .addStringOption(option =>
    option.setName('version')
      .setDescription('Version (optional)')
      .setRequired(false)
  );

module.exports = command;
