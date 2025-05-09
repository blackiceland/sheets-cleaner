import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081/api/v1/sheets/duplicates';

const rows = Array.from({ length: 10_000 }, (_, i) => [`row_${i}`]);
const payload = JSON.stringify({ rows });
const params = { headers: { 'Content-Type': 'application/json' } };

export const options = {
    scenarios: {
        duplicates_test: {
            executor: 'constant-arrival-rate',
            rate: 100,           // 100 RPS
            timeUnit: '1s',
            duration: '2m',
            preAllocatedVUs: 100,
            maxVUs: 200,
        },
    },
};

export default function () {
    const res = http.post(BASE_URL, payload, params);
    check(res, {
        'status is 5xx': (r) => r.status >= 500,
    });
}
