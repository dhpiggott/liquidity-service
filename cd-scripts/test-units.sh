#!/bin/bash

set -euo pipefail

sbt ";scalafmtSbtCheck ;scalafmtCheck ;service/test:scalafmtCheck
  ;coverage
  ;service/test
  ;coverageReport"
