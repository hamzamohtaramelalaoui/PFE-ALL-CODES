const { SlashCommandBuilder } = require('discord.js');

const command = new SlashCommandBuilder()
  .setName('refresh')
  .setDescription('Trigger the refresh workflow');

module.exports = command;
