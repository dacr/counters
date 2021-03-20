# ![](images/logo-base-64.png) Counters
Just count

```
BASE=http://127.0.0.1:8080
API=$BASE/api
curl -d '{"name":"test"}' -H "Content-Type: application/json" $API/group
GROUP=9c4242b9-b290-407a-b2a3-64d36a694762
curl -d '{"name":"counter#1"}' -H "Content-Type: application/json" $API/group/$GROUP/counter
COUNTER=d4254f7a-35b9-451e-b339-0604c2b4f4be
curl $API/group/$GROUP/counter/$COUNTER

curl $API/increment/$GROUP/$COUNTER

curl $BASE/$GROUP/count/$COUNTER

curl 
```