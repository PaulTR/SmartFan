
'use strict';

process.env.DEBUG = 'actions-on-google:*';

const functions = require('firebase-functions');

// The Firebase Admin SDK to access the Firebase Realtime Database. 
const admin = require('firebase-admin');
admin.initializeApp(functions.config().firebase);

const Assistant = require('actions-on-google').ApiAiAssistant;
const ACTION_FAN = 'fan';


exports.fanControl = functions.https.onRequest((req, res) => {

	
	const assistant = new Assistant({request: req, response: res});

	function fanControl (assistant) {
		var ref = admin.database().ref('/');
		if( req.body.result.parameters.control.toString() === 'on' ) {
			ref.update({"fanOn": true});
		} else {
			ref.update({"fanOn": false});
		}
	}

	const actionMap = new Map();

	actionMap.set(ACTION_FAN, fanControl);

	assistant.handleRequest(actionMap);
});