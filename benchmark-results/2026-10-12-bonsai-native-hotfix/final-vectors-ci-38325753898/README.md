# Final-source Vectors CI

Source `837ca23a95a88ed99657756ae6c348c4b866400d` passes [CI 38325753898](https://github.com/integrallis/vectors/actions/runs/38325753898). The staged Maven and Gradle consumers cover all 33 modules and resolve core SHA `13f0bd516ede8e4306541c72ef368e94c9bddddd80a99631ff5aa65992fdbba8`. This is not a Central publication receipt.

Portability XML reports zero failures/errors: ARM 2,131 tests with four unavailable-width skips; scalar 2,131 tests with 62 unavailable-SIMD skips; x86-128 2,135 tests with no skips. The manifest retains exact skip reasons, per-file hashes and the compressed original reports/consumer graphs. Performance qualification and the release dry run remain separate gates.
