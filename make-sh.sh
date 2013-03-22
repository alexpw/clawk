#!/bin/sh
lein uberjar && (echo '#!/usr/bin/env java -jar'; cat target/*-standalone.jar) > target/clawk
chmod u+x target/clawk
