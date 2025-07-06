# Sheets Data Cleaner Service

Java 17 · Spring Boot · Google Sheets Add-on backend

---

## 1&nbsp;· Overview
This micro-service receives rows from a Google Sheets add-on and returns
potential duplicates in two steps:

1. **Exact stage** – fast canonicalisation + equality check.  
2. **Fuzzy stage** – MinHash + heuristic metrics + optional neural similarity.

The stages are split into two HTTP endpoints so that a user can delete
obvious duplicates in the sheet before running the heavier fuzzy search.

---

## 2&nbsp;· API

### 2.1 `POST /api/v1/sheets/duplicates/exact`
Detects exact duplicates and returns an encrypted token for the next step.

Request (JSON)
```jsonc
{
  "rows": [["john", "smith"], ["smith", "john"], ["foo", "bar"]],
  "hasHeaders": false
}
```

Response (JSON)
```jsonc
{
  "datasetToken": "eyJh...",              // encrypted + signed
  "duplicateResponse": {
    "confirmed": [[0,1]],                  // exact dups
    "candidates": [],
    "meta": [{"idx":0,"clusterId":"...","kind":"CANON"}, ...],
    "rows": [ { "idx": 1, "rowId": 123456789012345678 }, ... ]
  }
}
```

Token payload
| field | meaning |
|-------|---------|
| `exp` | Unix-seconds TTL (default 30 min) |
| `rows`| Normalised rows (`idx, value, rowId`) |
| `exact`| Internal `ExactDetectionResult` |
| `hasHeader`| whether the header was removed before normalisation |

### 2.2 `POST /api/v1/sheets/duplicates/fuzzy`
Finishes duplicate detection using the token from step 1.

Request (JSON)
```jsonc
{
  "datasetToken": "eyJh...",               // from step 1
  "removedRowIds": [123456789012345678]    // rows deleted by the user after step 1
}
```

Response — standard `DuplicateMatchResponse` with **indexes already
shifted** if the original sheet had a header.

HTTP 400 is returned for:
* Invalid / expired / oversized token
* Zip-bomb (decompressed payload > 5 MB)

---

## 3&nbsp;· Security & Privacy

* **Stateless** — the service never writes user data to disk or DB.
* All rows are stored only inside the client-side `datasetToken`, encrypted
  with AES-GCM-128 and authenticated with HMAC-SHA-256.
* TTL ≤ 30 min; token size ≤ 2 MB; zip-bombs rejected.
* JWT/OAuth is used only to validate Google Workspace requests; the token
  discussed above is *internal* and never exposed to third parties.

---

## 4 · Environment variables
| Variable | Purpose | Default |
|----------|---------|---------|
| `TOKEN_SECRET` | 32-byte secret for token encryption/signature | `0123456789abcdef0123456789abcdef` |
| `EMBEDDING_BASE_URL` | URL of the neural similarity service | `http://localhost:5000/similarity` |
| `SPRING_PROFILES_ACTIVE` | `prod` / `test` | `prod` |

---

## 5 · Run locally
```bash
# compile & test
./mvnw clean test

# run
export TOKEN_SECRET="$(openssl rand -hex 16)"
./mvnw spring-boot:run
```
Service will be available at `http://localhost:8081`.

---

## 6 · Docker
```bash
docker build -t sheets-cleaner .

docker run -e TOKEN_SECRET=$(openssl rand -hex 16) -p 8081:8081 sheets-cleaner
```

---

## 7 · Calling examples
```bash
# 1) exact stage
echo '{"rows":[["john","smith"],["smith","john"]],"hasHeaders":false}' \
  | http POST :8081/api/v1/sheets/duplicates/exact > exact.json

TOKEN=$(jq -r .datasetToken exact.json)

# 2) fuzzy stage
printf '{"datasetToken":"%s","removedRowIds":[]}' "$TOKEN" \
  | http POST :8081/api/v1/sheets/duplicates/fuzzy
```

---

## 8 · License
© 2024 MAS Labs. MIT License.

## 9 · Front-end integration (Sheets add-on)

> Применяется только к логике разделённого пайплайна (rowId, datasetToken, removedRowIds).

1. **Exact step**
   ```http
   POST /api/v1/sheets/duplicates/exact
   Body: { rows, hasHeaders }
   → returns { datasetToken, duplicateResponse }
   ```
   • Отобразите `duplicateResponse` пользователю.  
   • Сохраните `datasetToken` в памяти (или в скрытом столбце).  
   • `hasHeaders=true` ⇒ индексы в ответе уже сдвинуты на +1.

2. **Track removals**
   • Когда пользователь удаляет строку, сохраните её `rowId`.  
     (храните `rowId` в скрытой колонке рядом с сырым текстом).

3. **Fuzzy step**
   ```http
   POST /api/v1/sheets/duplicates/fuzzy
   Body: { datasetToken, removedRowIds }
   → returns DuplicateMatchResponse (includes header shift)
   ```
   • `removedRowIds` — массив `long`, может быть пустым.  
   • Индексы в ответе уже корректны для листа с заголовком.

4. **Ошибка 400**  
   • Причины: просроченный / повреждённый / слишком большой токен.  
   • Действие: повторно вызвать **Exact step**.

Лимиты: токен ≤ 2 МБ, TTL ≤ 30 мин.  Если пользователь редактирует лист дольше 30 мин, просто запускаем exact заново. 