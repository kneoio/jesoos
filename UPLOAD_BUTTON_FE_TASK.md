# Frontend Task: Implement Dynamic Upload Button Activation

## Summary
Enable the upload button dynamically when the backend signals that the AI is about to execute the `upload_song` tool. The button should be hidden by default and activated via a WebSocket message.

## Requirements

### 1. WebSocket Message Handler
In the chat WebSocket message handler, detect and handle the `show_upload_button` message:

```json
{
  "type": "show_upload_button"
}
```

### 2. State Management
Add a reactive state variable to track upload button visibility:
- Variable name: `showUploadButton` (boolean)
- Initial value: `false` (button hidden)
- Update to `true` when `show_upload_button` message is received

### 3. UI Implementation
Bind the upload button to this state:
- Button should be **hidden** when `showUploadButton === false`
- Button should be **visible and enabled** when `showUploadButton === true`
- Button should remain visible for the entire chat session (or until explicitly hidden)

### 4. Message Parsing
In the existing WebSocket message handler:
```javascript
const message = JSON.parse(event.data)
if (message.type === "show_upload_button") {
  showUploadButton = true
}
```

## Implementation Details

### Location
- Vue Store: `vue-station-app/src/stores/chat.js`
- Chat Component: `vue-station-app/src/components/Chat.vue` (or similar)
- WebSocket Handler: wherever `onmessage` or WebSocket event listener is defined

### Expected Flow
1. User authenticates (verify_code succeeds)
2. Chat begins, upload button is hidden
3. User asks AI to upload a song
4. AI decides to call `upload_song` tool
5. Backend sends `{"type": "show_upload_button"}` message
6. Frontend receives message, parses it
7. Sets `showUploadButton = true`
8. Upload button appears in chat UI
9. User can now click to upload

## Testing
- Verify button is hidden on initial chat load
- Trigger upload_song tool in chat
- Confirm `show_upload_button` message arrives via WebSocket
- Verify button becomes visible immediately
- Test multiple uploads in same session

## Related Endpoints
- WebSocket: `/jesoos/ws/chat` (existing)
- Upload endpoint: `POST /jesoos/chat/upload-temp` (already implemented)
- Verify that upload is only enabled for artists (`is_artist: "true"`)
