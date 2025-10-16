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
update-readme:
	@echo "Updating README.md to version $(VERSION)..."
	@sed -i 's/Current version: \*\*[0-9]*\.[0-9]*\.[0-9]*\*\*/Current version: **$(VERSION)**/' README.md
	@echo "✓ README.md updated"

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

	@echo "Step 4/5: Updating README.md..."
	@$(MAKE) -s update-readme
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
