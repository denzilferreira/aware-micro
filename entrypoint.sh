#!/bin/sh

# Exit immediately if a command exits with a non-zero status
set -e

/aware-micro/generate_aware_config.sh
/aware-micro/gradlew $@
