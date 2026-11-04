PYTHON ?= python3
CARGO ?= cargo
GRADLE ?= gradle

.PHONY: check python rust fmt android android-session android-transport android-app clean

check: python rust android

python:
	$(PYTHON) tools/protocol_v1.py --selftest
	$(PYTHON) tools/check_doc.py
	$(PYTHON) tools/contract_v1.py --test

rust:
	cd daemon && $(CARGO) clippy --all-targets -- -D warnings
	cd daemon && $(CARGO) test --all-targets

fmt:
	cd daemon && $(CARGO) fmt --all

android:
	cd android/contracts && $(GRADLE) --no-daemon test
	cd android/ui-core && $(GRADLE) --no-daemon test
	cd android/session && $(GRADLE) --no-daemon test

android-session:
	cd android/session && $(GRADLE) --no-daemon test

android-transport:
	cd android/transport && $(GRADLE) --no-daemon testDebugUnitTest

android-app:
	cd android/app && $(GRADLE) --no-daemon assembleDebug

clean:
	cd daemon && $(CARGO) clean
	cd android/contracts && $(GRADLE) clean
	cd android/ui-core && $(GRADLE) clean
	cd android/session && $(GRADLE) clean
	cd android/transport && $(GRADLE) clean
	cd android/app && $(GRADLE) clean
