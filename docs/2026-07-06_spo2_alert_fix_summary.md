# SpO2 Alert Fix Summary

Problem:
SpO2 alerts were showing `ERROR` and sometimes firing with missing values, which made the alerts hard to trust and could show placeholder text like `?%`.

Fix:
We updated the alert labels so SpO2 warnings and emergencies use real titles instead of `ERROR`, and we added a render contract so missing SpO2 values are handled safely before dispatch. If the needed values are missing, the rule now suppresses or degrades instead of showing broken alert text.

Result:
SpO2 alerts now render with the correct category labels, and missing values no longer produce misleading `?` placeholders in the alert body.
