const { ApolloGateway, RemoteGraphQLDataSource } = require('@apollo/gateway');
const { ApolloServer } = require('apollo-server-lambda');
const { ApolloServerPluginLandingPageGraphQLPlayground } = require('apollo-server-core');
const { readFileSync } = require('fs');

// https://www.apollographql.com/docs/apollo-server/deployment/lambda

const supergraphSdl = readFileSync('./supergraph.graphql').toString();

class AuthenticatedDataSource extends RemoteGraphQLDataSource {
  willSendRequest({ request, context }) {
    request.http.headers.set('Authorization', context.authorization);
  }
}

const gateway = new ApolloGateway({
  supergraphSdl,
  buildService({ name, url }) {
    return new AuthenticatedDataSource({ url });
  },
});

const server = new ApolloServer({
  gateway,
  context: ({ event }) => {
    return { authorization: event.headers.authorization || '' };
  },
  introspection: true,
  plugins: [ApolloServerPluginLandingPageGraphQLPlayground()],
});

const cors = {
  allowedHeaders: ["Authorization", "Content-Type"],
  credentials: true,
  maxAge: 900,
  methods: ["GET", "HEAD", "POST"],
  origin: ["https://sysrev.com",
    "https://www.sysrev.com",
    "https://staging.sysrev.com"]
};

const opts = { expressGetMiddlewareOptions: { cors } };

exports.handler = server.createHandler(opts);
