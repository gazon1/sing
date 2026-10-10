# This file makes ``infra`` a Python package, which is required for
# ``import infra.kiwi.something`` to work (Python needs ``infra`` to be
# a registered package in ``sys.modules`` before it can import sub-packages).
#
# This is intentionally minimal: it exists solely to establish ``infra`` as
# a package.  All real code lives in sub-packages.
