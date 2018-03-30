#!/bin/bash

set -euo pipefail

sbt ";reload plugins ;sbt:scalafmt::test ;scalafmt::test
  ;reload return ;sbt:scalafmt::test ;scalafmt::test
  ;server/test:scalafmt::test ;coverage ;server/test ;coverageReport"
