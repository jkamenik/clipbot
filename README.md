# unbot

A simple extensible hipchat bot written in clojure

# Configure

```javascript
# resources/config.json
{
    "server-port": 9000,
    "bots": [{
        "id": "hipchat",
        "connection": {
            "type": "hipchat",
            "conf": {
                "user": "1234_567890@chat.hipchat.com",
                "pass": "**********",
                "nick": "Bert Bot",
                "mention": "@bert",
                "rooms": [
                  "1234_war_room",
                  "2345_lunch_room"
                ]
            }
        },
        "plugins": ["weather", "jenkins", "docker"]
    }]
}
```