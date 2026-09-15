.PHONY: help build test clean release tag-release

# Extract version from build.gradle.kts
VERSION := $(shell grep 'version = "' releva-sdk/build.gradle.kts | sed -n 's/.*version = "\([^"]*\)".*/\1/p')

# Default target
help:
	@echo "Releva SDK Release Makefile"
	@echo ""
	@echo "Available targets:"
	@echo "  make build           - Build the SDK"
	@echo "  make test            - Run tests"
	@echo "  make clean           - Clean build artifacts"
	@echo "  make release         - Release the current version ($(VERSION))"
	@echo ""

# Build the SDK
build:
	@echo "Building SDK..."
	./gradlew :releva-sdk:build
	@echo "✓ Build complete!"

# Run tests
test:
	@echo "Running tests..."
	./gradlew :releva-sdk:test
	@echo "✓ Tests passed!"

# Clean build artifacts
clean:
	@echo "Cleaning build artifacts..."
	./gradlew clean
	@echo "✓ Clean complete!"

# Update version in README.md
# Rewrite the dependency coordinate an integrator copies, everywhere it appears.
#
# This used to target `Current version: **X.Y.Z**`, a string that exists in neither
# README.md nor INTEGRATION_GUIDE.md — so sed matched nothing, changed nothing, and the
# step printed its tick anyway. That is why README sat at 1.3.0 through the 1.4.0 release
# and INTEGRATION_GUIDE.md, the file an integrator actually follows, sat at 1.0.0: four
# minor versions and a whole device-QA pass of fixes behind what the tag served.
#
# So it now rewrites the coordinate itself, and counts the lines it changed. A release step
# that silently does nothing is worse than one that is missing, because the log says it ran.
update-docs:
	@echo "Updating documented version to $(VERSION)..."
	@changed=0; \
	for f in README.md INTEGRATION_GUIDE.md; do \
		before=$$(grep -c 'com\.github\.Releva-ai:sdk-kotlin:[0-9]' $$f || true); \
		sed -i 's/com\.github\.Releva-ai:sdk-kotlin:[0-9][0-9.]*/com.github.Releva-ai:sdk-kotlin:$(VERSION)/g' $$f; \
		after=$$(grep -c 'com\.github\.Releva-ai:sdk-kotlin:$(VERSION)' $$f || true); \
		echo "  $$f: $$after of $$before coordinate(s) now at $(VERSION)"; \
		changed=$$((changed + after)); \
	done; \
	if [ "$$changed" -eq 0 ]; then \
		echo "ERROR: no dependency coordinate was rewritten — the docs do not match the"; \
		echo "       pattern this step looks for, so it would have reported success while"; \
		echo "       leaving every integrator on the previous version."; \
		exit 1; \
	fi
	@echo "✓ docs updated"

# Kept so `make update-readme` does not silently vanish for anyone with it in muscle memory.
update-readme: update-docs

# Create and push git tag
tag-release:
	@echo "Creating git tag $(VERSION)..."
	@if git rev-parse $(VERSION) >/dev/null 2>&1; then \
		echo "ERROR: Tag $(VERSION) already exists!"; \
		exit 1; \
	fi
	git tag -a $(VERSION) -m "Release version $(VERSION)"
	@echo "✓ Git tag created"
	@echo "Pushing tag to GitHub..."
	git push origin $(VERSION)
	@echo "✓ Tag pushed to GitHub"

# Main release target
release:
	@echo "========================================"
	@echo "  Releasing Releva SDK $(VERSION)"
	@echo "========================================"
	@echo ""

	@echo "Step 1/5: Cleaning build artifacts..."
	@$(MAKE) -s clean
	@echo ""

	@echo "Step 2/5: Running tests..."
	@$(MAKE) -s test
	@echo ""

	@echo "Step 3/5: Building SDK..."
	@$(MAKE) -s build
	@echo ""

	@echo "Step 4/5: Updating documented version..."
	@$(MAKE) -s update-docs
	@echo ""

	@echo "Step 5/5: Committing and tagging release..."
	@git add README.md
	@git commit -m "Release version $(VERSION)" || echo "No changes to commit"
	@git push origin master
	@$(MAKE) -s tag-release
	@echo ""

	@echo "========================================"
	@echo "  ✓ Release $(VERSION) Complete!"
	@echo "========================================"
	@echo ""
	@echo "Next steps:"
	@echo "  1. Verify build on JitPack: https://jitpack.io/#Releva-ai/sdk-kotlin/$(VERSION)"
	@echo "  2. Wait for green checkmark"
	@echo "  3. Update your apps to use version $(VERSION)"
	@echo ""
