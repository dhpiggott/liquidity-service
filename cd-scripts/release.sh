#!/bin/bash

set -euo pipefail

sbt ";ws-protocol/releaseEarly"
