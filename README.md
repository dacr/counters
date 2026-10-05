# ![](images/logo-base-64.png) Counters ![tests][tests-workflow] [![License][licenseImg]][licenseLink] [![][CountersImg]][CountersLnk]
Just count whatever you want. Three steps to create a counter :
1. Register, you get an API token and a default group
2. Optionally create other counters groups, each user only sees its own groups
3. Create your counter, its increments history is kept

It has been deployed on [https://mapland.fr/counters][deployed].
Visit [this link](https://mapland.fr/counters/d5d6a0be-7ba7-41cc-aa37-beb7d957bfa0/count/cfcd5fa9-f4cb-4426-a2de-e2238339158e)
to increment and get access to the counter state, or check [this page](https://mapland.fr/counters/d5d6a0be-7ba7-41cc-aa37-beb7d957bfa0/state/cfcd5fa9-f4cb-4426-a2de-e2238339158e) directly get 
the current counter state


## curl usage example
```
BASE=http://127.0.0.1:8080
API=$BASE/api

curl -d '{"name":"john","email":"john@example.com"}' -H "Content-Type: application/json" $API/user
# follow the validation link sent by email, the token can't be used before
# extract the token and the default group ID from the response, the token is given only once
TOKEN=uHU-cpiDtiu58ilpCtW95KvFA-kaSdmBY1K4DjXaewQ
GROUP=d5d6a0be-7ba7-41cc-aa37-beb7d957bfa0
AUTH="Authorization: Bearer $TOKEN"

curl -H "$AUTH" $API/group

curl -H "$AUTH" -d '{"name":"counter#1"}' -H "Content-Type: application/json" $API/group/$GROUP/counter
# extract the counter ID from the response
COUNTER=cfcd5fa9-f4cb-4426-a2de-e2238339158e

curl -H "$AUTH" $API/group/$GROUP/counter/$COUNTER

curl -H "$AUTH" $API/group/$GROUP/counter/$COUNTER/state

# the token is not required when the counter has been created with "publicIncrement":true
curl -H "$AUTH" -X POST $API/group/$GROUP/counter/$COUNTER/increment

curl -H "$AUTH" "$API/group/$GROUP/counter/$COUNTER/history?limit=10"

# public pages, no token
curl $BASE/$GROUP/count/$COUNTER

curl $BASE/$GROUP/state/$COUNTER
```

The complete API documentation is available at `$BASE/swagger`.

## Quick local start

Thanks to [scala-cli][scl],
this application is quite easy to start, just execute :
```
scala-cli --dep fr.janalyse::counters:1.0.6 -e 'counters.Main.main(args)'
```

## Configuration

| Environment variable            | Description                                                         | default value           |
|---------------------------------|---------------------------------------------------------------------|-------------------------|
| COUNTERS_LISTEN_IP              | Listening network interface                                         | "0.0.0.0"               |
| COUNTERS_LISTEN_PORT            | Listening port                                                      | 8080                    |
| COUNTERS_PREFIX                 | Add a prefix to all defined routes                                  | ""                      |
| COUNTERS_URL                    | How this service is known from outside, used in emailed links       | "http://127.0.0.1:8080" |
| COUNTERS_STORE_PATH             | Where data is stored                                                | "/tmp/counters-data"    |
| COUNTERS_EMAIL_VALIDATION_DELAY | How long a registration waits for its email validation              | 48h                     |
| COUNTERS_MAIL_FROM              | Sender of the emails, such as the registration validation           | "counters@localhost"    |
| COUNTERS_MAIL_REPLY_TO          | Reply-To of the emails                                              |                         |
| COUNTERS_SMTP_HOST              | SMTP server, without it emails are only logged (development)        |                         |
| COUNTERS_SMTP_PORT              | SMTP server port                                                    | 465                     |
| COUNTERS_SMTP_TLS               | implicit (usually port 465), starttls (usually port 587) or none    | "implicit"              |
| COUNTERS_SMTP_USERNAME          | SMTP authentication user name                                       |                         |
| COUNTERS_SMTP_PASSWORD          | SMTP authentication password                                        |                         |

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
