require('dotenv').config();
const fs = require('node:fs');
const path = require('node:path');
const { REST, Routes } = require('discord.js');

const { TOKEN, CLIENT_ID, GUILD_ID } = process.env;

if (!TOKEN || !CLIENT_ID || !GUILD_ID) {
  throw new Error('Missing TOKEN, CLIENT_ID, or GUILD_ID in .env');
}

const rest = new REST({ version: '10' }).setToken(TOKEN);
const commandsPath = path.join(__dirname, 'commands');
const commands = fs
  .readdirSync(commandsPath)
  .filter(file => file.endsWith('.js'))
  .map(file => require(path.join(commandsPath, file)).toJSON());

(async () => {
  try {
    console.log(`Registering ${commands.length} commands...`);

    await rest.put(
      Routes.applicationGuildCommands(
        CLIENT_ID,
        GUILD_ID
      ),
      { body: commands }
    );

    console.log('Commands registered!');
  } catch (error) {
    console.error(error);
  }
})();
