const { SlashCommandBuilder } = require('discord.js');

const command = new SlashCommandBuilder()
  .setName('ls')
  .setDescription('List items from the ls workflow')
  .addStringOption(option =>
    option.setName('uid')
      .setDescription('UID (optional)')
      .setRequired(false)
  )
  .addStringOption(option =>
    option.setName('label')
      .setDescription('Label (optional)')
      .setRequired(false)
  );

module.exports = command;
