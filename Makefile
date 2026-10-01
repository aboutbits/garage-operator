### SETUP

init:
	$(MAKE) install

install:
	./gradlew --console=colored :operator:quarkusBuild

### EXECUTION

run:
	./gradlew --console=colored :operator:quarkusDev

test:
	./gradlew --console=colored :operator:clean :operator:test --rerun-tasks

### UTILITIES

lint:
	rumdl check .

# Flag targets as phony, to tell `make` that these are no file targets
.PHONY: init install run test lint
