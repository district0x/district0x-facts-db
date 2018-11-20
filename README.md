# district0x-facts-db.core

## Index server
### Build
```bash
clj -A:build-index-server
```

### Run
```bash
node index-server.js --address 0x1994a5281cc200e7516e02cac1e707eb6cfa388e
                     --port 1234
                     --schema db_schema.edn
                     --rpc localhost:8549
```
