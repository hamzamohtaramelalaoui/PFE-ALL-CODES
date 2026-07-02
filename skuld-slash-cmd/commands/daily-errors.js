const { SlashCommandBuilder } = require('discord.js');

const command = new SlashCommandBuilder()
  .setName('daily-errors')
  .setDescription('Check daily errors')
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
