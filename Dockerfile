FROM clojure
RUN mkdir -p /usr/src/app/
ENV RETROCRON_DB /var/db/retrocron
WORKDIR /usr/src/app/
COPY ./project.clj /usr/src/app/
RUN lein deps
COPY . /usr/src/app
RUN mv "$(lein uberjar | sed -n 's/^Created \(.*standalone\.jar\)/\1/p')" app-standalone.jar
CMD lein ragtime migrate && java -jar app-standalone.jar