# ![](images/logo-base-64.png) Counters
Just count

```
BASE=http://127.0.0.1:8080
API=$BASE/api
curl -d '{"name":"test"}' -H "Content-Type: application/json" $API/group
GROUP=d5d6a0be-7ba7-41cc-aa37-beb7d957bfa0
curl -d '{"name":"counter#1"}' -H "Content-Type: application/json" $API/group/$GROUP/counter
COUNTER=cfcd5fa9-f4cb-4426-a2de-e2238339158e
curl $API/group/$GROUP/counter/$COUNTER

curl $API/increment/$GROUP/$COUNTER

curl $BASE/$GROUP/count/$COUNTER

curl http://127.0.0.1:8080/$GROUP/state/$COUNTER
```