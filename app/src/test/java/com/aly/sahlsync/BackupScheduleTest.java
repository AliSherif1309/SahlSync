package com.aly.sahlsync;

import java.time.*;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class BackupScheduleTest {
 private final ZoneId zone=ZoneId.of("Asia/Qatar");
 private void expect(String expected,String after,int hour,int minute,int days){
  assertEquals(Instant.parse(expected).toEpochMilli(),BackupSchedule.next(Instant.parse(after),zone,hour,minute,days));
 }
 @Test public void dailyCanRunInAMinute(){
  expect("2026-11-18T20:31:00Z","2026-11-18T20:30:00Z",23,31,127);
 }
 @Test public void weeklySkipsDaysAndWrapsAtTheSelectedMinute(){
  int wednesday=1<<2;
  expect("2026-11-25T20:30:00Z","2026-11-18T20:30:00Z",23,30,wednesday);
 }
 @Test public void dailyWrapsToTomorrowAfterTheSelectedMinute(){
  expect("2026-11-19T20:30:00Z","2026-11-18T20:31:00Z",23,30,127);
 }
}
