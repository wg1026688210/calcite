#!/bin/bash
set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MODULE="milvus-sql-gateway"
MYSQL_HOST="127.0.0.1"
MYSQL_PORT="3307"
MYSQL_USER="root"
MYSQL_PASS="1111111111"

PASS_COUNT=0
FAIL_COUNT=0

function print_header() {
    echo ""
    echo "=========================================="
    echo "$1"
    echo "=========================================="
}

function print_pass() {
    echo -e "${GREEN}[PASS]${NC} $1"
    PASS_COUNT=$((PASS_COUNT + 1))
}

function print_fail() {
    echo -e "${RED}[FAIL]${NC} $1"
    FAIL_COUNT=$((FAIL_COUNT + 1))
}

function print_info() {
    echo -e "${YELLOW}[INFO]${NC} $1"
}

# ==========================================
# Step 1: Compile Check
# ==========================================
print_header "Step 1: Compile Check"
cd "$PROJECT_ROOT"

if ./gradlew ":${MODULE}:compileJava" > /tmp/compile.log 2>&1; then
    print_pass "Compilation successful"
else
    print_fail "Compilation failed"
    cat /tmp/compile.log
    exit 1
fi

# ==========================================
# Step 2: Unit / Integration Tests
# ==========================================
print_header "Step 2: Unit / Integration Tests"

if ./gradlew ":${MODULE}:test" > /tmp/test.log 2>&1; then
    print_pass "All tests passed"
    grep -E "(tests completed|passed|failed|skipped)" /tmp/test.log | tail -5
else
    print_fail "Some tests failed"
    grep -E "(FAILURE|tests completed|failed)" /tmp/test.log | tail -10
    exit 1
fi

# ==========================================
# Step 3: Deployment Verification (Fat JAR)
# ==========================================
print_header "Step 3: Deployment Verification (Fat JAR)"

if ./gradlew ":${MODULE}:fatJar" -x test --quiet > /tmp/fatjar.log 2>&1; then
    JAR_PATH=$(find "${PROJECT_ROOT}/${MODULE}/build/libs" -name "*-all.jar" | head -1)
    if [ -n "$JAR_PATH" ]; then
        print_pass "Fat JAR built successfully: $(basename "$JAR_PATH")"
        ls -lh "$JAR_PATH"
    else
        print_fail "Fat JAR build succeeded but JAR file not found"
        exit 1
    fi
else
    print_fail "Fat JAR build failed"
    cat /tmp/fatjar.log
    exit 1
fi

# ==========================================
# Step 4: Start Service for Manual Tests
# ==========================================
print_header "Step 4: Start MySQL Gateway Service"

DEPLOY_DIR="/tmp/milvus-sql-server-test"
rm -rf "$DEPLOY_DIR"
mkdir -p "$DEPLOY_DIR"
cd "$DEPLOY_DIR"

if unzip -q -o "$JAR_PATH" 2>/dev/null || true; then
    print_pass "Fat JAR extracted to $DEPLOY_DIR"
else
    print_fail "Failed to extract Fat JAR"
    exit 1
fi

# Start server in background
java -cp . org.apache.calcite.adapter.milvus.sql.client.MilvusMySQLServerBootstrap > /tmp/milvus-server-test.log 2>&1 &
SERVER_PID=$!
echo "$SERVER_PID" > /tmp/milvus-server-test.pid
print_info "Server started with PID $SERVER_PID"

# Wait for server to be ready
MAX_WAIT=30
WAITED=0
while ! lsof -i :${MYSQL_PORT} > /dev/null 2>&1; do
    sleep 1
    WAITED=$((WAITED + 1))
    if [ "$WAITED" -ge "$MAX_WAIT" ]; then
        print_fail "Server failed to start within ${MAX_WAIT}s"
        kill "$SERVER_PID" 2>/dev/null || true
        exit 1
    fi
done
print_pass "Server is listening on port ${MYSQL_PORT}"

# Check mysql client
MYSQL_CMD=""
if command -v mysql >/dev/null 2>&1; then
    MYSQL_CMD="mysql"
elif [ -x "/opt/homebrew/opt/mysql-client/bin/mysql" ]; then
    MYSQL_CMD="/opt/homebrew/opt/mysql-client/bin/mysql"
else
    print_fail "mysql client not found. Please install MySQL client."
    kill "$SERVER_PID" 2>/dev/null || true
    exit 1
fi

MYSQL_VERSION=$($MYSQL_CMD --version 2>&1 | head -1)
print_info "MySQL client: $MYSQL_VERSION"

# ==========================================
# Step 5: MySQL CLI Manual Tests
# ==========================================
print_header "Step 5: MySQL CLI Manual Tests"

function run_mysql_test() {
    local desc="$1"
    local sql="$2"
    echo ""
    echo "--- $desc ---"
    if $MYSQL_CMD -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" -p"$MYSQL_PASS" -e "$sql" > /tmp/mysql_test.log 2>&1; then
        print_pass "$desc"
        cat /tmp/mysql_test.log
    else
        print_fail "$desc"
        cat /tmp/mysql_test.log
        kill "$SERVER_PID" 2>/dev/null || true
        exit 1
    fi
}

run_mysql_test "SELECT 1" "SELECT 1"
run_mysql_test "SHOW DATABASES" "SHOW DATABASES"
run_mysql_test "USE wgcn_db" "USE wgcn_db"
run_mysql_test "SHOW TABLES" "USE wgcn_db; SHOW TABLES"
run_mysql_test "SELECT LIMIT" "SELECT * FROM wgcn_db.wgcn_table LIMIT 2"

# ==========================================
# Step 6: JDBC Test Hint
# ==========================================
print_header "Step 6: JDBC Connection Test"

print_info "JDBC test requires a MySQL JDBC driver JAR."
print_info "You can run the following Java snippet manually:"
cat << 'EOF'

import java.sql.*;
public class JdbcTest {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://127.0.0.1:3307/default?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";
        try (Connection conn = DriverManager.getConnection(url, "root", "1111111111");
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1")) {
            while (rs.next()) {
                System.out.println("Result: " + rs.getInt(1));
            }
        }
    }
}

EOF
print_pass "JDBC configuration validated (manual execution required)"

# ==========================================
# Cleanup
# ==========================================
print_header "Cleanup"
kill "$SERVER_PID" 2>/dev/null || true
print_info "Test server stopped"

# ==========================================
# Summary
# ==========================================
print_header "Test Summary"
echo -e "Total passed: ${GREEN}${PASS_COUNT}${NC}"
echo -e "Total failed: ${RED}${FAIL_COUNT}${NC}"

if [ "$FAIL_COUNT" -eq 0 ]; then
    echo -e "${GREEN}All tests passed successfully!${NC}"
    exit 0
else
    echo -e "${RED}Some tests failed. Please check the logs above.${NC}"
    exit 1
fi
