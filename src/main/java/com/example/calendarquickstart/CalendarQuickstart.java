package com.example.calendarquickstart;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.client.util.store.DataStore;
import com.google.api.client.util.store.DataStoreFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.*;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.people.v1.PeopleService;
import com.google.api.services.people.v1.PeopleServiceScopes;
import com.google.api.services.people.v1.model.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;


import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SpringBootApplication
public class CalendarQuickstart {
	private static final String APPLICATION_NAME = "Google Calendar API Java Quickstart";

	private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

	private static final String TOKENS_DIRECTORY_PATH = "tokens";


	private static final List<String> SCOPES =
			Arrays.asList(CalendarScopes.CALENDAR, DriveScopes.DRIVE, PeopleServiceScopes.CONTACTS_READONLY);
	private static final String CREDENTIALS_FILE_PATH = "/credentials.json";

	// Global instance of the Calendar client.
	private static Calendar client;
	// Global instance of the event datastore.
	private static DataStore<String> eventDataStore;
	// Global instance of the sync settings datastore.
	private static DataStore<String> syncSettingsDataStore;
	// The key in the sync settings datastore that holds the current sync token.
	private static final String SYNC_TOKEN_KEY = "syncToken";



	private static Credential getCredentials(final NetHttpTransport HTTP_TRANSPORT)
			throws IOException {
		InputStream in = CalendarQuickstart.class.getResourceAsStream(CREDENTIALS_FILE_PATH);
		if (in == null) {
			throw new FileNotFoundException("Resource not found: " + CREDENTIALS_FILE_PATH);
		}
		GoogleClientSecrets clientSecrets =
				GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));


		GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
				HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES)
				.setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY_PATH)))
				.setAccessType("offline")
				.build();
		LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
		Credential credential = new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
		return credential;
	}

	private static String extractFileIdFromUrl(String url) {
		String fileId = null;
		try {
			URL fileUrl = new URL(url);
			String path = fileUrl.getPath();
			Pattern pattern = Pattern.compile("file/d/(.*?)/");
			Matcher matcher = pattern.matcher(path);
			if (matcher.find()) {
				fileId = matcher.group(1);
			}
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}
		return fileId;
	}

	private static void run() throws IOException {
		// Construct the {@link Calendar.Events.List} request, but don't execute it yet.
		Calendar.Events.List request = client.events().list("primary");

		// Load the sync token stored from the last execution, if any.
		String syncToken = syncSettingsDataStore.get(SYNC_TOKEN_KEY);
		if (syncToken == null) {
			System.out.println("Performing full sync.");

			// Set the filters you want to use during the full sync. Sync tokens aren't compatible with
			// most filters, but you may want to limit your full sync to only a certain date range.
			// In this example we are only syncing events up to a year old.
			java.util.Calendar calendar = java.util.Calendar.getInstance();

			// 1년 전으로 시간을 되돌립니다.
			calendar.add(java.util.Calendar.YEAR, -1);
			Date oneYearAgo = calendar.getTime();
			request.setTimeMin(new DateTime(oneYearAgo, TimeZone.getTimeZone("UTC")));



		} else {
			System.out.println("Performing incremental sync.");
			request.setSyncToken(syncToken);
		}

		// Retrieve the events, one page at a time.
		String pageToken = null;
		Events events = null;
		do {
			request.setPageToken(pageToken);

			try { //토큰 유효하지 않을 때
				events = request.execute();
			} catch (GoogleJsonResponseException e) {
				if (e.getStatusCode() == 410) {
					// A 410 status code, "Gone", indicates that the sync token is invalid.
					System.out.println("Invalid sync token, clearing event store and re-syncing.");
					syncSettingsDataStore.delete(SYNC_TOKEN_KEY); //저장된 동기화 토큰 삭제
					eventDataStore.clear(); //이벤트 데이터 저장소 지우기
					run(); //전체동기화 수행
				} else {
					throw e;
				}
			}

			List<Event> items = events.getItems();
			if (items.size() == 0) {
				System.out.println("No new events to sync.");
			} else {
				for (Event event : items) {
					syncEvent(event);
				}
			}

			pageToken = events.getNextPageToken();
		} while (pageToken != null);

		// Store the sync token from the last request to be used during the next execution.
		syncSettingsDataStore.set(SYNC_TOKEN_KEY, events.getNextSyncToken());

		System.out.println("Sync complete.");
	}

	/**
	 * Sync an individual event. In this example we simply store it's string represenation to a file
	 * system data store.
	 */
	private static void syncEvent(Event event) throws IOException {
		if ("cancelled".equals(event.getStatus()) && eventDataStore.containsKey(event.getId())) {
			eventDataStore.delete(event.getId());
			System.out.println(String.format("Deleting event: ID=%s", event.getId()));
		} else {
			eventDataStore.set(event.getId(), event.toString());
			System.out.println(
					String.format("Syncing event: ID=%s, Name=%s", event.getId(), event.getSummary()));
		}
	}



	public static void addAttachment(Calendar calendarService, Drive driveService, String calendarId,
									 String eventId, String fileUrl) throws IOException {
		String fileId = extractFileIdFromUrl(fileUrl);
		File file = driveService.files().get(fileId).execute();
		Event event = calendarService.events().get(calendarId, eventId).execute();

		List<EventAttachment> attachments = event.getAttachments();
		if (attachments == null) {
			attachments = new ArrayList<EventAttachment>();
		}
		attachments.add(new EventAttachment()
				.setFileUrl("https://drive.google.com/file/d/" + fileId + "/view?usp=drive_link")
				.setMimeType(file.getMimeType())
				.setTitle(file.getName()));

		Event changes = new Event()
				.setAttachments(attachments);
		calendarService.events().patch(calendarId, eventId, changes)
				.setSupportsAttachments(true)
				.execute();
	}



	public static void main(String... args) throws IOException, GeneralSecurityException {

		final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
		Credential credential = getCredentials(HTTP_TRANSPORT);

		Calendar calendarService =
				new Calendar.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
						.setApplicationName(APPLICATION_NAME)
						.build();

		Drive driveService =
				new Drive.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential)
						.setApplicationName(APPLICATION_NAME)
						.build();
		PeopleService peopleService = new PeopleService.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials(HTTP_TRANSPORT))
				.setApplicationName(APPLICATION_NAME)
				.build();



		ListConnectionsResponse response = peopleService.people().connections()
				.list("people/me")
				.setPersonFields("names,emailAddresses,memberships")
				.execute();

		List<Person> connections = response.getConnections();
		if (connections != null && connections.size() > 0) {
			for (Person person : connections) {
				List<Membership> memberships = person.getMemberships();
				if (memberships != null && memberships.size() > 0) {
					for (Membership membership : memberships) {
						ContactGroupMembership groupMembership = membership.getContactGroupMembership();
						if (groupMembership != null) {
							String group = groupMembership.getContactGroupId();
							// Check if the contact is in the desired group.
							if ("Me".equals(group)) {
								// Add to attendees.
							}
						}
					}
				}
			}
		}

		DateTime now = new DateTime(System.currentTimeMillis());
		Events events = calendarService.events().list("primary")
				.setMaxResults(10)
				.setTimeMin(now)
				.setOrderBy("startTime")
				.setSingleEvents(true)
				.execute();
		List<Event> items = events.getItems();
		if (items.isEmpty()) {
			System.out.println("No upcoming events found.");
		} else {
			System.out.println("Upcoming events");
			for (Event event : items) {
				DateTime start = event.getStart().getDateTime();
				if (start == null) {
					start = event.getStart().getDate();
				}
				System.out.printf("%s (%s)\n", event.getSummary(), start);
			}
		}
		Event event = new Event()
				.setSummary("Google I/O 2023")
				.setLocation("800 Howard St., San Francisco, CA 94103")
				.setDescription("A chance to hear more about Google's developer products.");

		DateTime startDateTime = new DateTime("2023-08-07T09:00:00-07:00");
		EventDateTime start = new EventDateTime()
				.setDateTime(startDateTime)
				.setTimeZone("America/Los_Angeles");
		event.setStart(start);

		DateTime endDateTime = new DateTime("2023-08-07T17:00:00-07:00");
		EventDateTime end = new EventDateTime()
				.setDateTime(endDateTime)
				.setTimeZone("America/Los_Angeles");
		event.setEnd(end);

		ConferenceSolutionKey conferenceSKey = new ConferenceSolutionKey();
		conferenceSKey.setType("hangoutsMeet");
		CreateConferenceRequest createConferenceReq = new CreateConferenceRequest();
		createConferenceReq.setRequestId(UUID.randomUUID().toString());
		createConferenceReq.setConferenceSolutionKey(conferenceSKey);
		ConferenceData conferenceData = new ConferenceData();
		conferenceData.setCreateRequest(createConferenceReq);
		event.setConferenceData(conferenceData);

		String[] recurrence = new String[] {"RRULE:FREQ=DAILY;COUNT=2"};
		event.setRecurrence(Arrays.asList(recurrence));

//		EventAttendee[] attendees = new EventAttendee[] {
//				new EventAttendee().setEmail("lpage@example.com"),
//				new EventAttendee().setEmail("sbrin@example.com"),
//				new EventAttendee().setEmail("yejisin64@gmail.com")
//		};
//		event.setAttendees(Arrays.asList(attendees));

		EventAttendee[] attendees = connections.stream()
				.filter(person -> person.getEmailAddresses() != null && !person.getEmailAddresses().isEmpty())
				.map(person -> new EventAttendee().setEmail(person.getEmailAddresses().get(0).getValue()))
				.toArray(EventAttendee[]::new);
		event.setAttendees(Arrays.asList(attendees));

		EventReminder[] reminderOverrides = new EventReminder[] {
				new EventReminder().setMethod("email").setMinutes(24 * 60),
				new EventReminder().setMethod("popup").setMinutes(10),
		};
		Event.Reminders reminders = new Event.Reminders()
				.setUseDefault(false)
				.setOverrides(Arrays.asList(reminderOverrides));
		event.setReminders(reminders);

		String calendarId = "primary";

		//event = calendarService.events().insert(calendarId, event).execute();
		event = calendarService.events().insert(calendarId, event).setConferenceDataVersion(1).execute();
		System.out.printf("Event created: %s\n", event.getHtmlLink());

		String fileUrl = "https://drive.google.com/file/d/1vfrDhHcB8uRam6xMlXQHIgb_m4wjZotQ/view?usp=drive_link";
		addAttachment(calendarService, driveService, calendarId, event.getId(), fileUrl);

		run();

		SpringApplication.run(CalendarQuickstart.class, args);
	}
}
