#!/bin/bash

PRIVATE_API_TOKEN=${PRIVATE_API_TOKEN:-dev-token}

# Start the server in the background
PRIVATE_API_TOKEN="$PRIVATE_API_TOKEN" ./gradlew run --no-daemon &
SERVER_PID=$!

# Wait a bit for the server to start
sleep 3

echo "Testing file server..."

# Test 1: Upload a file
echo "Test 1: Uploading file..."
curl -X PUT -H "Authorization: Bearer $PRIVATE_API_TOKEN" -d "Hello, World!" http://localhost:9001/file/test-file
echo ""

# Test 2: Get the file back
echo "Test 2: Getting file back..."
curl -X GET -H "Authorization: Bearer $PRIVATE_API_TOKEN" http://localhost:9001/file/test-file
echo ""

# Test 3: Generate public URL
echo "Test 3: Generating public URL..."
curl -X POST -H "Authorization: Bearer $PRIVATE_API_TOKEN" -H "Content-Type: application/json" -d '{"duration": 5}' http://localhost:9001/file/test-file/public-url
echo ""

# Test 4: Access via public URL (you'll need to extract the URL from the response above and test manually)
echo "Test 4: You can now test the public URL manually"

# Cleanup
kill $SERVER_PID
wait $SERVER_PID 2>/dev/null
