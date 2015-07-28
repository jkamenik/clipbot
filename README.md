clipbot
=======

A simple extensible hipchat bot written in clojure

## Configuring it ##

Place the following in one of two locations:

1. `/etc/clipbot.json`
1. `~/.clipbot.json`

```javascript
{
    "server-port": 9000,
    "bots": [{
        "id": "clipbot",
        "connection": {
            "type": "hipchat",
            "conf": {
                "user": "...",
                "pass": "...",
                "nick": "...",
                "mention": "@...",
                "rooms": [
                    "someroom",
                    "another room"
                ]
            }
        },
        "plugins": [jenkins]
    }]
}
```


## Testing it ##

Easiest is use the repl

```bash
$ lein repl
=> (start-app)
;; make a code change
=> (reload-app)
;; finish up
=> (stop-app)
```

## Adding a plugin ##

## TODO ##

* [ ] Pull passwords from the ENV
* [ ] Auto join/leave
* [ ] Enable/disable plugins per room
* [ ] Load all plugins by default
