.PHONY: help build test clean release check-version update-version tag-release

# Default target
help:
	@echo "Releva SDK Release Makefile"
	@echo ""
	@echo "Available targets:"
	@echo "  make build           - Build the SDK"
	@echo "  make test            - Run tests"
	@echo "  make clean           - Clean build artifacts"
	@echo "  make release VERSION=x.y.z - Release a new version"
	@echo ""
	@echo "Examples:"
	@echo "  make release VERSION=1.0.1"
	@echo "  make release VERSION=1.1.0"
	@echo "  make release VERSION=2.0.0"
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

# Check if VERSION is provided
check-version:
ifndef VERSION
	@echo "ERROR: VERSION is required!"
	@echo "Usage: make release VERSION=x.y.z"
	@echo "Example: make release VERSION=1.0.1"
	@exit 1
endif
	@echo "Version: $(VERSION)"

# Update version in build.gradle.kts
update-version: check-version
	@echo "Updating version to $(VERSION)..."
	@sed -i 's/version = "[0-9]*\.[0-9]*\.[0-9]*"/version = "$(VERSION)"/' releva-sdk/build.gradle.kts
	@sed -i 's/Current version: \*\*[0-9]*\.[0-9]*\.[0-9]*\*\*/Current version: **$(VERSION)**/' README.md
	@echo "✓ Version updated in build.gradle.kts and README.md"

# Create and push git tag
tag-release: check-version
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
release: check-version
	@echo "========================================"
	@echo "  Releasing Releva SDK $(VERSION)"
	@echo "========================================"
	@echo ""

	@echo "Step 1/6: Cleaning build artifacts..."
	@$(MAKE) -s clean
	@echo ""

	@echo "Step 2/6: Running tests..."
	@$(MAKE) -s test
	@echo ""

	@echo "Step 3/6: Building SDK..."
	@$(MAKE) -s build
	@echo ""

	@echo "Step 4/6: Updating version numbers..."
	@$(MAKE) -s update-version VERSION=$(VERSION)
	@echo ""

	@echo "Step 5/6: Committing changes..."
	@git add releva-sdk/build.gradle.kts README.md
	@git commit -m "Release version $(VERSION)" || echo "No changes to commit"
	@git push origin main
	@echo "✓ Changes committed and pushed"
	@echo ""

	@echo "Step 6/6: Creating and pushing git tag..."
	@$(MAKE) -s tag-release VERSION=$(VERSION)
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
