.PHONY: help build test clean release check-version update-version tag-release

# Colors for output
RED=\033[0;31m
GREEN=\033[0;32m
YELLOW=\033[1;33m
NC=\033[0m # No Color

# Default target
help:
	@echo "$(GREEN)Releva SDK Release Makefile$(NC)"
	@echo ""
	@echo "Available targets:"
	@echo "  $(YELLOW)make build$(NC)           - Build the SDK"
	@echo "  $(YELLOW)make test$(NC)            - Run tests"
	@echo "  $(YELLOW)make clean$(NC)           - Clean build artifacts"
	@echo "  $(YELLOW)make release VERSION=x.y.z$(NC) - Release a new version"
	@echo ""
	@echo "Examples:"
	@echo "  make release VERSION=1.0.1"
	@echo "  make release VERSION=1.1.0"
	@echo "  make release VERSION=2.0.0"
	@echo ""

# Build the SDK
build:
	@echo "$(GREEN)Building SDK...$(NC)"
	./gradlew :releva-sdk:build
	@echo "$(GREEN)✓ Build complete!$(NC)"

# Run tests
test:
	@echo "$(GREEN)Running tests...$(NC)"
	./gradlew :releva-sdk:test
	@echo "$(GREEN)✓ Tests passed!$(NC)"

# Clean build artifacts
clean:
	@echo "$(YELLOW)Cleaning build artifacts...$(NC)"
	./gradlew clean
	@echo "$(GREEN)✓ Clean complete!$(NC)"

# Check if VERSION is provided
check-version:
ifndef VERSION
	@echo "$(RED)ERROR: VERSION is required!$(NC)"
	@echo "Usage: make release VERSION=x.y.z"
	@echo "Example: make release VERSION=1.0.1"
	@exit 1
endif
	@echo "$(GREEN)Version: $(VERSION)$(NC)"

# Update version in build.gradle.kts
update-version: check-version
	@echo "$(YELLOW)Updating version to $(VERSION)...$(NC)"
	@sed -i 's/version = "[0-9]*\.[0-9]*\.[0-9]*"/version = "$(VERSION)"/' releva-sdk/build.gradle.kts
	@sed -i 's/Current version: \*\*[0-9]*\.[0-9]*\.[0-9]*\*\*/Current version: **$(VERSION)**/' README.md
	@echo "$(GREEN)✓ Version updated in build.gradle.kts and README.md$(NC)"

# Create and push git tag
tag-release: check-version
	@echo "$(YELLOW)Creating git tag $(VERSION)...$(NC)"
	@if git rev-parse $(VERSION) >/dev/null 2>&1; then \
		echo "$(RED)ERROR: Tag $(VERSION) already exists!$(NC)"; \
		exit 1; \
	fi
	git tag -a $(VERSION) -m "Release version $(VERSION)"
	@echo "$(GREEN)✓ Git tag created$(NC)"
	@echo "$(YELLOW)Pushing tag to GitHub...$(NC)"
	git push origin $(VERSION)
	@echo "$(GREEN)✓ Tag pushed to GitHub$(NC)"

# Main release target
release: check-version
	@echo "$(GREEN)========================================$(NC)"
	@echo "$(GREEN)  Releasing Releva SDK $(VERSION)$(NC)"
	@echo "$(GREEN)========================================$(NC)"
	@echo ""

	@echo "$(YELLOW)Step 1/6: Cleaning build artifacts...$(NC)"
	@$(MAKE) -s clean
	@echo ""

	@echo "$(YELLOW)Step 2/6: Running tests...$(NC)"
	@$(MAKE) -s test
	@echo ""

	@echo "$(YELLOW)Step 3/6: Building SDK...$(NC)"
	@$(MAKE) -s build
	@echo ""

	@echo "$(YELLOW)Step 4/6: Updating version numbers...$(NC)"
	@$(MAKE) -s update-version VERSION=$(VERSION)
	@echo ""

	@echo "$(YELLOW)Step 5/6: Committing changes...$(NC)"
	@git add releva-sdk/build.gradle.kts README.md
	@git commit -m "Release version $(VERSION)" || echo "$(YELLOW)No changes to commit$(NC)"
	@git push origin main
	@echo "$(GREEN)✓ Changes committed and pushed$(NC)"
	@echo ""

	@echo "$(YELLOW)Step 6/6: Creating and pushing git tag...$(NC)"
	@$(MAKE) -s tag-release VERSION=$(VERSION)
	@echo ""

	@echo "$(GREEN)========================================$(NC)"
	@echo "$(GREEN)  ✓ Release $(VERSION) Complete!$(NC)"
	@echo "$(GREEN)========================================$(NC)"
	@echo ""
	@echo "Next steps:"
	@echo "  1. Verify build on JitPack: https://jitpack.io/#Releva-ai/sdk-kotlin/$(VERSION)"
	@echo "  2. Wait for green ✓ checkmark"
	@echo "  3. Update your apps to use version $(VERSION)"
	@echo ""
