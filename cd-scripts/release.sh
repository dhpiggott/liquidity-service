#!/bin/bash

set -euo pipefail

# So sbt-dynver derives the version correctly
git fetch --unshallow

sbt ";ws-protocol/releaseEarly"
