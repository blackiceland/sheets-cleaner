import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    vus: 10,
    iterations: 100,
};

const BASE = __ENV.BASE_URL || 'http://localhost:8081';
const API  = `${BASE}/api/v1/sheets/duplicates`;

function rows(count, cellSize = 10) {
    const cell = 'x'.repeat(cellSize);
    return Array.from({ length: count }, () => [cell, cell, cell]);
}

const bigJson = JSON.stringify({ rows: rows(12_000, 80) });
const okJson  = JSON.stringify({ rows: rows(1_000, 20) });

export default function () {
    const ok = http.post(API, okJson, { headers: { 'Content-Type': 'application/json' } });
    const big = http.post(API, bigJson, { headers: { 'Content-Type': 'application/json' } });

    check(ok,  { '200 OK': (r) => r.status === 200 });
    check(big, { '413 too large': (r) => r.status === 413 });

    sleep(1);
}
