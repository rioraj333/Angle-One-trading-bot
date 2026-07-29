import {
  AngularNodeAppEngine,
  createNodeRequestHandler,
  isMainModule,
  writeResponseToNodeResponse,
} from '@angular/ssr/node';
import express from 'express';
import { join } from 'node:path';

const browserDistFolder = join(import.meta.dirname, '../browser');
const API_TARGET = 'http://localhost:8080';

const app = express();
const angularApp = new AngularNodeAppEngine();

/**
 * Forward /api/** to the Java backend. This has to happen before the Angular
 * SSR catch-all below — otherwise Angular's server-side router intercepts every
 * request (including API calls) and renders the app shell instead, which is
 * why the browser sees "<!DOCTYPE ..." where it expects JSON.
 */
app.use('/api', express.raw({ type: '*/*', limit: '5mb' }), async (req, res) => {
  try {
    const excludedHeaders = ['host', 'connection', 'content-length', 'expect', 'transfer-encoding'];
    const headers: Record<string, string> = {};
    for (const [key, value] of Object.entries(req.headers)) {
      if (typeof value === 'string' && !excludedHeaders.includes(key.toLowerCase())) {
        headers[key] = value;
      }
    }

    const bodyBuffer = req.body as Buffer;
    const hasBody = req.method !== 'GET' && req.method !== 'HEAD' && bodyBuffer?.length > 0;
    const response = await fetch(`${API_TARGET}${req.originalUrl}`, {
      method: req.method,
      headers,
      body: hasBody ? new Uint8Array(bodyBuffer) : undefined,
    });

    res.status(response.status);
    response.headers.forEach((value, key) => {
      if (!['content-encoding', 'content-length', 'transfer-encoding'].includes(key.toLowerCase())) {
        res.setHeader(key, value);
      }
    });
    res.send(Buffer.from(await response.arrayBuffer()));
  } catch (err) {
    res.status(502).json({ status: false, message: `Backend unreachable: ${(err as Error).message}` });
  }
});

/**
 * Serve static files from /browser
 */
app.use(
  express.static(browserDistFolder, {
    maxAge: '1y',
    index: false,
    redirect: false,
  }),
);

/**
 * Handle all other requests by rendering the Angular application.
 */
app.use((req, res, next) => {
  angularApp
    .handle(req)
    .then((response) =>
      response ? writeResponseToNodeResponse(response, res) : next(),
    )
    .catch(next);
});

/**
 * Start the server if this module is the main entry point, or it is ran via PM2.
 * The server listens on the port defined by the `PORT` environment variable, or defaults to 4000.
 */
if (isMainModule(import.meta.url) || process.env['pm_id']) {
  const port = process.env['PORT'] || 4000;
  app.listen(port, (error) => {
    if (error) {
      throw error;
    }

    console.log(`Node Express server listening on http://localhost:${port}`);
  });
}

/**
 * Request handler used by the Angular CLI (for dev-server and during build) or Firebase Cloud Functions.
 */
export const reqHandler = createNodeRequestHandler(app);
