#!/bin/bash

set -euo pipefail

sbt ";service/it:scalafmtCheck
  ;service/docker:publishLocal
  ;service/it:testOnly *LiquidityServerComponentSpec
  ;service/docker:clean"
