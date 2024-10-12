# ![](images/logo-base-64.png) Counters ![tests][tests-workflow] [![License][licenseImg]][licenseLink] [![][CountersImg]][CountersLnk]
Just count whatever you want. Two steps to create a counter :
1. Create a counters group
2. Create your counter

It has been deployed on [https://mapland.fr/counters][deployed].
Visit [this link](https://mapland.fr/counters/d5d6a0be-7ba7-41cc-aa37-beb7d957bfa0/count/cfcd5fa9-f4cb-4426-a2de-e2238339158e)
to increment and get access to the counter state, or check [this page](https://mapland.fr/counters/d5d6a0be-7ba7-41cc-aa37-beb7d957bfa0/state/cfcd5fa9-f4cb-4426-a2de-e2238339158e) directly get 
the current counter state


## curl usage example
```
BASE=http://127.0.0.1:8080
API=$BASE/api

curl -d '{"name":"test"}' -H "Content-Type: application/json" $API/group
# extract the group ID from the response
GROUP=d5d6a0be-7ba7-41cc-aa37-beb7d957bfa0

curl -d '{"name":"counter#1"}' -H "Content-Type: application/json" $API/group/$GROUP/counter
# extract the counter ID from the response
COUNTER=cfcd5fa9-f4cb-4426-a2de-e2238339158e

curl $API/group/$GROUP/counter/$COUNTER

curl $API/increment/$GROUP/$COUNTER

curl $BASE/$GROUP/count/$COUNTER

curl $BASE/$GROUP/state/$COUNTER
```

## Quick local start

Thanks to [scala-cli][scl],
this application is quite easy to start, just execute :
```
scala-cli --dep fr.janalyse::counters:1.0.6 -e 'counters.Main.main(args)'
```

## Configuration

| Environment variable | Description                            | default value           |
|----------------------|----------------------------------------|-------------------------|
| COUNTERS_LISTEN_IP   | Listening network interface            | "0.0.0.0"               |
| COUNTERS_LISTEN_PORT | Listening port                         | 8080                    |
| COUNTERS_PREFIX      | Add a prefix to all defined routes     | ""                      |
| COUNTERS_URL         | How this service is known from outside | "http://127.0.0.1:8080" |
| COUNTERS_STORE_PATH  | Where data is stored                   | "/tmp/counters-data"    |

[cs]: https://get-coursier.io/
[scl]: https://scala-cli.virtuslab.org/

[deployed]:   https://mapland.fr/counters
[akka-http]:  https://doc.akka.io/docs/akka-http/current/index.html

[Counters]:       https://github.com/dacr/counters
[CountersImg]: https://img.shields.io/maven-central/v/fr.janalyse/counters_2.13.svg
[CountersLnk]: https://search.maven.org/#search%7Cga%7C1%7Cfr.janalyse.counters

[tests-workflow]: https://github.com/dacr/counters/actions/workflows/scala.yml/badge.svg

[licenseImg]: https://img.shields.io/github/license/dacr/counters.svg
[licenseLink]: LICENSE
