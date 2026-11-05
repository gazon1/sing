export default {
  "*.{kt,kts}": (files) => [`python3 scripts/ktlint-lint.py ${files.join(" ")}`],
};
