import { execute } from './engine/js/engine.js';
self.onmessage = ({ data }) => {
  try { self.postMessage({ id: data.id, result: JSON.parse(execute(data.request)) }); }
  catch (error) { self.postMessage({ id: data.id, error: String(error?.message || error) }); }
};
self.postMessage({ ready: true });
