Issue: WEAR-431
Summary: Backend for editing a dropped marker's title & location
Status: Design
Assignee: tommy <tommy@ascentitech.com>
Reporter: tommy <tommy@ascentitech.com>
Created: 2026-03-06T12:30:58.742-0600
Updated: 2026-03-06T12:53:13.659-0600
Description:
Summary:
This issue involves creating a backend solution for editing the title and location of dropped markers in the application. The functionality will support requests for changes and ensure that the updates are reflected in the marker hashmap and the dropped marker list.
Context:
The front end for this feature is outlined in . The backend needs to handle requests for location and title changes, upsert these changes into the marker hashmap within CotDispatchService, dispatch out the newly updated cot (using the same uid for that marker from the map), and update the list of dropped markers that the front end utilizes. This ticket may concern snapshotMarker() function, since this function actually generates the uuids for markers. 
Acceptance criteria:
- Handle requests for location and title changes.
- Upsert changes into the marker hashmap in CotDispatchService.
- Update the dropped marker list collected by the front end.
- Allow users to change the marker to the watch’s current location when changing location.
- Utilize similar edit functionality as the tak servers.
- Use ViewModel for managing the dropped marker list.
- Ensure any changes to the dropped marker list are called in the ViewModel, not the UI layer.
- Modify functions and data structures in the repository to ensure changes are reflected in the ViewModel.
- Ensure all calls to the dataRepository in the UI go through the ViewModel.
Other information:
- Issue Key: WEAR-409
- Assignee: Molly Izzi
- Created: 2026-02-24
- Reporter: Molly Izzi
- Status: In Progress
- Priority: High
- Parent Issue: WEAR-405
- Description:
- When changing location, give users the option to change the marker to the watch’s current location.
- Use similar edit functionality as the tak servers.
- Call the droppedMarkerList in the ViewModel; any changes coming from this functionality should be managed in the ViewModel, not the UI layer.
- Change function and data structure in the repository, with functions called in the ViewModel when changes occur.
- WEAR-431 will be be based off of WEAR-409 front end changes. 