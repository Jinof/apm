PYTHON ?= python3
PROMISE_CLI ?= promise
GRADLE ?= ./gradlew

.PHONY: format promise-check test android-check check

format:
	$(PROMISE_CLI) format apm.promise --write

promise-check:
	$(PROMISE_CLI) format apm.promise --check
	$(PROMISE_CLI) lint apm.promise
	$(PROMISE_CLI) check apm.promise --json

test:
	PYTHONPATH=src $(PYTHON) -m unittest discover -s tests -v

android-check:
	cd android && $(GRADLE) testDebugUnitTest lintDebug assembleDebug

check: promise-check test android-check
	PYTHONPATH=src $(PYTHON) -m compileall -q src tests
