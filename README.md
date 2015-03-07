# clipbot

A simple extensible hipchat bot written in clojure

## Plugins

### Docker

This plugin runs docker containers and streams their output back to HipChat.

It can be invoked using: `/docker run` in any room that Clipbot is present.

You must provide environment variables that point to your docker host, for example:

```
export DOCKER_HOST=tcp://192.168.59.103:2376
export DOCKER_CERT_PATH=/path/to/certs/dir
export DOCKER_TLS_VERIFY=1
```
