import {availableParallelism} from 'node:os';

// Leave CPU capacity for the server and recovery workers. Bound browser memory
// use on larger hosts, and keep a serial override for constrained machines.
export function browserWorkers(cpus = availableParallelism(), override = process.env.QREVY_BROWSER_WORKERS) {
  if (override === undefined || override === '') return Math.max(1, Math.min(4, Math.floor(cpus / 2)));
  if (/^[1-9]\d*%$/.test(override)) return override;
  if (/^[1-9]\d*$/.test(override) && Number.isSafeInteger(Number(override))) return Number(override);
  throw new Error('QREVY_BROWSER_WORKERS must be a positive integer or percentage.');
}
