# Google Assistant SDK for Node

This wrapper allows you to use the Google Assistant api in any Node application.
It handles events, audio buffering, and client connection automatically.

## Installation
```sh
npm install google-assistant-node
```

## Usage
```js
let GoogleAssistant = require('google-assistant-node');
let constants = GoogleAssistant.Constants;
let encodings = constants.Encoding;

let assistant = new GoogleAssistant({
  input: {
    encoding: encodings.LINEAR16,
    sampleRateHertz: 16000
  },
  output: {
    encoding: encodings.MP3,
    sampleRateHertz: 16000,
    volumePercentage: 100
  }
});

assistant.on('audio-data', (data) => {
  // Audio Data (bytes)
});

assistant.on('response-text', (text) => {
  //  Reponse Text (string)
});

assistant.on('request-text', (text) => {
  //  Request Text (string)
});

assistant.on('state', (state) => {
  //  Conversation State (bytes)
});

assistant.on('mic-mode', (mode) => {
  //  Microphone Mode (int)
});

assistant.on('unauthorized', (error) => {
  // Authorization error (error)
  // E.g. Did not authenticate with OAuth client
})

assistant.on('error', (error) => {
  //  Error (error)
});

assistant.on('ready', (writeStream) => {
  // Assistant is ready to accept audio data
  audioData.pipe(writeStream);
});

assistant.on('end', () => {
  // Conversation is over. 
}

// Use Google OAuth Client to authenticate: 
// https://github.com/google/google-auth-library-nodejs 
// or
// https://github.com/google/google-api-nodejs-client
assistant.authenticate(authClient);

// Start conversation
assistant.converse();
```

