#!/bin/bash

set -euo pipefail

sbt ";server/it:scalafmtCheck
  ;server/docker:publishLocal
  ;server/it:testOnly *LiquidityServerComponentSpec
  ;server/docker:clean"
