#!/bin/bash

set -euo pipefail

sbt ";scalafmtSbtCheck ;scalafmtCheck ;server/test:scalafmtCheck
  ;coverage
  ;server/test
  ;coverageReport"
