PYTHON ?= python3
PROMISE_CLI ?= promise
GRADLE ?= ./gradlew
APM_BUILD_DIR ?= /private/tmp/apm-build-$(shell uuidgen)

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
	@build_dir="$(APM_BUILD_DIR)"; \
	case "$$build_dir" in /private/tmp/*) ;; *) echo "APM_BUILD_DIR must be under /private/tmp"; exit 1;; esac; \
	test ! -e "$$build_dir" || { echo "Refusing to overwrite existing build: $$build_dir"; exit 1; }; \
	(cd android && $(GRADLE) -PapmIsolatedBuildDir="$$build_dir" testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest) && \
	$(PYTHON) scripts/verify-api-only.py "$$build_dir/outputs/apk/debug/app-debug.apk" "$$build_dir/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

check: promise-check test android-check
	PYTHONPATH=src $(PYTHON) -m compileall -q src tests
