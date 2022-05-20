const https = require('https');
const util = require('util');
const zlib = require('zlib');

// Modified from https://medium.com/@gevorggalstyan/how-to-promisify-node-js-http-https-requests-76a5a58ed90c
const request = async (options, postData) => {
  return new Promise((resolve, reject) => {
    const req = https.request(options, res => {
      if (res.statusCode < 200 || res.statusCode >= 300) {
        return reject(new Error(`Status Code: ${res.statusCode}`));
      }

      const data = [];

      res.on('data', chunk => {
        data.push(chunk);
      });

      res.on('end', () => resolve(Buffer.concat(data).toString()));
    });

    req.on('error', reject);

    if (postData) {
      req.write(postData);
    }

    // IMPORTANT
    req.end();
  });
};

exports.handler = async (event, context) => {
  const payload = Buffer.from(event.awslogs.data, 'base64');
  const unparsed = zlib.gunzipSync(payload).toString('utf8');
  const events = JSON.parse(unparsed).logEvents;
  const messages = events.map(function (event) { return event.message });
  const opts = {
    headers:
    {
      "Authorization": "Bearer " + process.env.SLACK_TOKEN,
      "Content-Type": "application/json"
    },
    host: "slack.com",
    method: "POST",
    path: "/api/chat.postMessage"
  };
  const body = {
    channel: process.env.SLACK_CHANNEL,
    text: "*Env*: " + process.env.ENV + "\n" + messages.join("\n")
  };
  try {
    const data = await request(opts, JSON.stringify(body));
    console.log(data);
  } catch (error) {
    console.error(error);
  }
  return `Successfully processed ${events.length} log events.`;
};
