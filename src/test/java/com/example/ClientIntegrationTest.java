package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

@SpringBootTest(classes = {Client.class, ClientIntegrationTest.TestConfig.class})
public class ClientIntegrationTest {
  private final PrintStream standardOut = System.out;
  private final ByteArrayOutputStream outputStreamCaptor = new ByteArrayOutputStream();

  @Autowired Client client;

  @TempDir static File tempDir;

  static class TestConfig {
    @Bean
    public File eventsFile() {
      return new File(tempDir, "events.json");
    }
  }

  @BeforeEach
  public void setUp() {
    System.setOut(new PrintStream(outputStreamCaptor));
  }

  @AfterEach
  public void tearDown() {
    System.setOut(standardOut);
  }

  @Test
  @SneakyThrows
  void shouldWriteTwoEventsWhenUserRunsServer() {
    // when
    client.run("up");

    // then
    String output = getOutput();
    assertTrue(output.contains("Starting..."));
    assertTrue(output.contains("Status: UP") || output.contains("Status: FAILED"));
  }

  @Test
  @SneakyThrows
  void shouldPrintNoEventsWhenUserRunsStatus() {
    // when
    client.run("status");

    // then
    assertEquals("No events found", getOutput());
  }

  @Test
  @SneakyThrows
  void shouldPrintUsageWhenNoArgsProvided() {
    // When
    client.run();

    // Then
    Set<String> expectedLines =
        Set.of(
            "Usage: vpn-client <command> [options]",
            "Commands:",
            "  status",
            "  up",
            "  down",
            "  history");
    assertOutputContainsLines(expectedLines);
  }

  @Test
  @SneakyThrows
  void shouldPrintAlreadyUpWhenServerIsUp() {
    // Given
    Event upEvent =
        new Event(
            Status.UP, LocalDateTime.now().minusMinutes(5).toEpochSecond(ZoneOffset.UTC) * 1000);
    writeEventsToFile(List.of(upEvent));

    // When
    client.run("up");

    // Then
    assertEquals("Already UP", getOutput());
  }

  @Test
  @SneakyThrows
  void shouldStopServerWhenUserRunsDown() {
    // When
    client.run("down");

    String output = getOutput();
    assertTrue(output.contains("Stopping..."));
    assertTrue(output.contains("Status: DOWN") || output.contains("Status: FAILED"));
  }

  @Test
  @SneakyThrows
  void shouldPrintAlreadyDownWhenServerIsDown() {
    // Given
    Event downEvent =
        new Event(
            Status.DOWN, LocalDateTime.now().minusMinutes(1).toEpochSecond(ZoneOffset.UTC) * 1000);
    writeEventsToFile(List.of(downEvent));

    // When
    client.run("down");

    assertEquals("Already DOWN", getOutput());
  }

  @Test
  @SneakyThrows
  void shouldPrintHistoryWhenEventsExist() {
    // Given
    Event upEvent =
        new Event(
            Status.UP, LocalDateTime.now().minusMinutes(5).toEpochSecond(ZoneOffset.UTC) * 1000);
    Event downEvent =
        new Event(
            Status.DOWN, LocalDateTime.now().minusMinutes(1).toEpochSecond(ZoneOffset.UTC) * 1000);
    writeEventsToFile(List.of(upEvent, downEvent));

    // When
    client.run("history");

    // Then
    String output = getOutput();
    assertTrue(output.contains("Status: UP"));
    assertTrue(output.contains("Status: DOWN"));
  }

  @Test
  @SneakyThrows
  void shouldPrintNoEventsInHistoryWhenNoneExist() {
    // When
    client.run("history");

    // Then
    assertEquals("No events found", getOutput());
  }

  @Test
  @SneakyThrows
  void shouldPrintFilteredHistoryBasedOnStatus() {
    // Given
    Event upEvent =
        new Event(
            Status.UP, LocalDateTime.now().minusMinutes(5).toEpochSecond(ZoneOffset.UTC) * 1000);
    Event downEvent =
        new Event(
            Status.DOWN, LocalDateTime.now().minusMinutes(1).toEpochSecond(ZoneOffset.UTC) * 1000);
    writeEventsToFile(List.of(upEvent, downEvent));

    // When
    client.run("history", "-S", "UP");

    // Then
    assertTrue(getOutput().contains("Status: UP"));
    assertFalse(getOutput().contains("Status: DOWN"));
  }

  @Test
  @SneakyThrows
  void shouldPrintUnknownCommandForInvalidInput() {
    // When
    client.run("invalidCommand");

    // Then
    assertEquals("Unknown command: invalidCommand", getOutput());
  }

  @Test
  @SneakyThrows
  void shouldStopServerWhenServerIsUp() {
    // Given
    Event upEvent =
        new Event(
            Status.UP, LocalDateTime.now().minusMinutes(5).toEpochSecond(ZoneOffset.UTC) * 1000);
    writeEventsToFile(List.of(upEvent));

    // When
    client.run("down");

    // Then
    assertTrue(getOutput().contains("Stopping..."));
    assertTrue(
        getOutput().contains("Status: DOWN") || getOutput().contains("Status: FAILED"),
        "Expected output to contain either 'Status: DOWN' or 'Status: FAILED'");
  }

  @Test
  @SneakyThrows
  void shouldPrintFilteredHistoryBasedOnDateRange() {
    // Given
    Event oldEvent =
        new Event(
            Status.UP, LocalDateTime.of(2023, 1, 1, 0, 0).toEpochSecond(ZoneOffset.UTC) * 1000);
    Event recentEvent =
        new Event(
            Status.DOWN, LocalDateTime.of(2023, 5, 1, 0, 0).toEpochSecond(ZoneOffset.UTC) * 1000);
    writeEventsToFile(List.of(oldEvent, recentEvent));

    // When - filter for events in the year 2023, before May 1
    client.run("history", "-f", "2023-01-01", "-t", "2023-04-30");

    // Then
    assertTrue(getOutput().contains("Status: UP"));
    assertFalse(getOutput().contains("Status: DOWN"));
  }

  @Test
  @SneakyThrows
  void shouldPrintHistoryInDescendingOrder() {
    // Given
    Event firstEvent =
        new Event(Status.UP, LocalDateTime.now().minusDays(2).toEpochSecond(ZoneOffset.UTC) * 1000);
    Event laterEvent =
        new Event(
            Status.DOWN, LocalDateTime.now().minusDays(1).toEpochSecond(ZoneOffset.UTC) * 1000);
    writeEventsToFile(List.of(firstEvent, laterEvent));

    // When
    client.run("history", "-s", "desc");

    // Then
    String output = getOutput();
    assertTrue(output.indexOf("Status: DOWN") < output.indexOf("Status: UP"));
  }

  @Test
  @SneakyThrows
  void shouldHandleEmptyEventsFileGracefully() {
    // Given
    writeEventsToFile(List.of());

    // When
    client.run("status");

    // Then
    assertEquals("No events found", getOutput());
  }

  @Test
  @SneakyThrows
  void shouldHandleFailedAfterUp() {
    // Given
    Event upEvent =
        new Event(
            Status.UP, LocalDateTime.now().minusMinutes(10).toEpochSecond(ZoneOffset.UTC) * 1000);
    Event failedEvent =
        new Event(
            Status.FAILED,
            LocalDateTime.now().minusMinutes(5).toEpochSecond(ZoneOffset.UTC) * 1000);
    writeEventsToFile(List.of(upEvent, failedEvent));

    // When
    client.run("status");

    // Then
    String output = getOutput();
    assertTrue(output.contains("Status: UP"));
  }

  @Test
  @SneakyThrows
  void shouldHandleFailedAfterDown() {
    // Given
    Event downEvent =
        new Event(
            Status.DOWN, LocalDateTime.now().minusMinutes(10).toEpochSecond(ZoneOffset.UTC) * 1000);
    Event failedEvent =
        new Event(
            Status.FAILED,
            LocalDateTime.now().minusMinutes(5).toEpochSecond(ZoneOffset.UTC) * 1000);
    writeEventsToFile(List.of(downEvent, failedEvent));

    // When
    client.run("status");

    // Then
    String output = getOutput();
    assertTrue(output.contains("Status: DOWN"));
  }

  @Test
  @SneakyThrows
  void shouldHandleWhenLastStatusUp() {
    // Given
    Event upEvent =
        new Event(
            Status.UP, LocalDateTime.now().minusMinutes(5).toEpochSecond(ZoneOffset.UTC) * 1000);
    writeEventsToFile(List.of(upEvent));

    // When
    client.run("status");

    // Then
    String output = getOutput();
    assertTrue(output.contains("Status: UP"));
  }

  @Test
  @SneakyThrows
  void shouldHandleWhenLastStatusDown() {
    // Given
    Event upEvent =
        new Event(
            Status.DOWN, LocalDateTime.now().minusMinutes(5).toEpochSecond(ZoneOffset.UTC) * 1000);
    writeEventsToFile(List.of(upEvent));

    // When
    client.run("status");

    // Then
    String output = getOutput();
    assertTrue(output.contains("Status: DOWN"));
  }

  @Test
  @SneakyThrows
  void shouldHandleLastStatusStarting() {
    // Given
    Event startingEvent =
        new Event(
            Status.STARTING,
            LocalDateTime.now().minusMinutes(5).toEpochSecond(ZoneOffset.UTC) * 1000);
    writeEventsToFile(List.of(startingEvent));

    // When
    client.run("status");

    // Then
    String output = getOutput();
    assertTrue(output.contains("Status: STARTING"));
  }

  @Test
  @SneakyThrows
  void shouldHandleLastStatusStopping() {
    // Given
    Event startingEvent =
        new Event(
            Status.STOPPING,
            LocalDateTime.now().minusMinutes(5).toEpochSecond(ZoneOffset.UTC) * 1000);
    writeEventsToFile(List.of(startingEvent));

    // When
    client.run("status");

    // Then
    String output = getOutput();
    assertTrue(output.contains("Status: STOPPING"));
  }

  @Test
  @SneakyThrows
  void shouldHandleFailedAfterStarting() {
    // Given
    Event startingEvent =
        new Event(
            Status.STARTING,
            LocalDateTime.now().minusMinutes(10).toEpochSecond(ZoneOffset.UTC) * 1000);
    Event failedEvent =
        new Event(
            Status.FAILED,
            LocalDateTime.now().minusMinutes(5).toEpochSecond(ZoneOffset.UTC) * 1000);
    writeEventsToFile(List.of(startingEvent, failedEvent));

    // When
    client.run("status");

    // Then
    String output = getOutput();
    assertTrue(output.contains("No events found"));
  }

  @Test
  @SneakyThrows
  void shouldHandleFailedAfterStopping() {
    // Given
    Event startingEvent =
        new Event(
            Status.STARTING,
            LocalDateTime.now().minusMinutes(10).toEpochSecond(ZoneOffset.UTC) * 1000);
    Event failedEvent =
        new Event(
            Status.FAILED,
            LocalDateTime.now().minusMinutes(5).toEpochSecond(ZoneOffset.UTC) * 1000);
    writeEventsToFile(List.of(startingEvent, failedEvent));

    // When
    client.run("status");

    // Then
    String output = getOutput();
    assertTrue(output.contains("No events found"));
  }

  private String getOutput() {
    return outputStreamCaptor.toString().trim();
  }

  private List<String> getOutputLines() {
    return List.of(outputStreamCaptor.toString().trim().split("\\R"));
  }

  private void assertOutputContainsLines(Set<String> expectedLines) {
    List<String> actualLines = getOutputLines();
    for (String expectedLine : expectedLines) {
      assertTrue(actualLines.contains(expectedLine), "Expected line not found: " + expectedLine);
    }
  }

  private void writeEventsToFile(List<Event> events) throws Exception {
    ObjectMapper objectMapper = new ObjectMapper();
    File eventsFile = new File(tempDir, "events.json");
    objectMapper.writeValue(eventsFile, events);
  }
}
