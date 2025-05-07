import http from 'k6/http';
import { check } from 'k6';

export const options = {
    vus: 10,
    iterations: 100,
    thresholds: {
        'http_req_duration': ['p(95)<5000'],
        'http_req_failed':   ['rate<0.01'],
    },
};

const url = 'http://host.docker.internal:8080/api/v1/sheets/duplicates';
const payload = JSON.stringify({
    rows: [
        ['Anton Markov'],
        ['Markov Anton']
    ]
});
const params = { headers: { 'Content-Type': 'application/json' } };

export default function () {
    const res = http.post(url, payload, params);
    check(res, { 'status 200': r => r.status === 200 });
}
