# Play Console "App content" answers

Play Console → Policy and programs → App content. Answers below match the code as of
versionCode 2 (1.0.0). Recheck them whenever networking, permissions or SDKs change.

## Privacy policy

https://github.com/abimjoshi7/creds/blob/main/docs/play/privacy-policy.md
(Fill in DEVELOPER_NAME and CONTACT_EMAIL in that file first.)

## App access

"All functionality is available without special access." There is no login: the reviewer
creates a vault with any master password of 10+ characters on first launch.

## Ads

No, the app does not contain ads.

## Content rating (IARC questionnaire)

Category: "All other app types" (utility). Answer No to every content question
(violence, sexuality, language, controlled substances, gambling, user-generated content
shared with others, location sharing, purchases). Expected rating: Everyone / PEGI 3.

## Target audience

18 and over (or 13+). Not designed for children; do not opt into Families.

## News app / COVID / Government

No / No / No.

## Financial features

"My app doesn't provide any financial features." (It stores card details the user types
in, but offers no payments, banking, loans or trading.)

## Health

No health features.

## Data safety

- Does your app collect or share any of the required user data types? **No.**
  - Nothing is transferred off the device, except the optional breach check, which sends a
    5-character prefix of a SHA-1 hash. That prefix is shared by hundreds of passwords, is
    not linked to the user, and is not a Play "user data" type, so it is not declared as
    collection. If you prefer the cautious route, declare instead:
    *App activity → Other actions*, not shared, collected ephemerally, optional,
    purpose *App functionality*.
- Is all user data encrypted in transit? **Yes** (HTTPS only; cleartext is disabled).
- Do you provide a way for users to request deletion? Data is only on the device;
  answer **No** to "collects data" makes this N/A. The privacy policy explains that
  uninstalling or clearing storage deletes everything.
- Independent security review (MASA): No.

## Permissions declarations

None needed. The app uses no restricted permissions: no SMS/call log, no
`QUERY_ALL_PACKAGES` (it uses a `<queries>` launcher filter), no accessibility service,
no location, no background location.

## Autofill

`AutofillService` needs no Play declaration. Accessibility is not used.

## Advertising ID

No. The app does not use the advertising ID (no ads or analytics SDKs).
