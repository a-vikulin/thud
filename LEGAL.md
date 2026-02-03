# Legal Information

## What TreadmillHUD Does

TreadmillHUD is an independent Android application that communicates with iFit-equipped treadmills via their local gRPC interface. It provides:

- A heads-up display overlay showing real-time workout metrics
- Structured workout execution with HR/Power-based auto-adjustments
- Integration with external heart rate monitors and foot pods
- Export to standard FIT format for Garmin Connect and other platforms

TreadmillHUD connects **locally** to your treadmill (via localhost) and does not interact with iFit's cloud services.

## Certificate Requirement

To authenticate with the treadmill's local interface, TreadmillHUD requires mTLS certificates that must be obtained separately. These certificates can be extracted from your own treadmill's firmware using tools available in a separate repository.

**TreadmillHUD itself does not contain any extracted credentials, certificates, or proprietary code.**

## Interoperability Rights

The use of locally-extracted certificates for device interoperability is explicitly permitted under:

### United States
**17 U.S.C. § 1201(f)** - The DMCA interoperability exception permits circumvention of technological measures for the purpose of achieving interoperability with independently created computer programs.

### European Union
**Directive 2009/24/EC, Article 6** - The EU Software Directive permits decompilation and reverse engineering when necessary to achieve interoperability, provided:
- It is performed by a licensed user
- The information is not already readily available
- It is limited to parts necessary for interoperability
- The information is not used for other purposes

### Other Jurisdictions
Many countries have similar interoperability exceptions in their copyright laws. Consult local legal resources for your jurisdiction.

## Intended Use

TreadmillHUD is intended **exclusively** for:

- Controlling and monitoring treadmills you legally own
- Enabling interoperability with equipment you purchased
- Personal, non-commercial fitness tracking

## Not Intended For

- Accessing devices you do not own
- Commercial exploitation or resale
- Bypassing security on systems you don't have authorization to access
- Any use that violates applicable laws or terms of service

## Disclaimer

TreadmillHUD is provided "as is" for **personal interoperability purposes**. Users are responsible for ensuring their use complies with applicable laws in their jurisdiction.

The authors are not affiliated with iFit, NordicTrack, ProForm, Freemotion, or any related entities. All trademarks are property of their respective owners.

## Why Interoperability Matters

Consumers who purchase connected fitness equipment should be able to:

- Use their equipment without mandatory subscription services
- Integrate their equipment with third-party fitness apps
- Access data from hardware they own
- Continue using equipment if the manufacturer discontinues support

TreadmillHUD supports these consumer rights through legitimate interoperability.
