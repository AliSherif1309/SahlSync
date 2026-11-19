package com.aly.sahlsync;

import android.app.*;
import android.widget.*;
import android.view.View;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONObject;

final class ScheduleEditor {
 private final Spinner mode;
 private Button dateButton,timeButton;
 private final LinearLayout days;
 private final CheckBox[] weekdays=new CheckBox[7];
 private LocalDate date;
 private LocalTime time;

 ScheduleEditor(Activity activity,LinearLayout form,JSONObject pair){
  ZonedDateTime soon=ZonedDateTime.now().plusMinutes(3);
  date=Instant.ofEpochMilli(pair.optLong("scheduleDate",soon.toInstant().toEpochMilli()))
   .atZone(ZoneId.systemDefault()).toLocalDate();
  time=LocalTime.of(pair.optInt("scheduleHour",soon.getHour()),pair.optInt("scheduleMinute",soon.getMinute()));
  mode=new Spinner(activity);
  mode.setContentDescription("Backup schedule");
  ArrayAdapter<String> options=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,
   new String[]{"Manual only","Once on a date","Every day","Selected weekdays","Every hour","Every 6 hours"});
  options.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
  mode.setAdapter(options);
  form.addView(SahlTheme.label(activity,"Backup schedule",16,SahlTheme.TEXT));
  form.addView(mode);
  dateButton=SahlTheme.button(activity,dateLabel(),()->new DatePickerDialog(activity,(view,y,m,d)->{
   date=LocalDate.of(y,m+1,d);
   dateButton.setText(dateLabel());
  },date.getYear(),date.getMonthValue()-1,date.getDayOfMonth()).show());
  timeButton=SahlTheme.button(activity,timeLabel(),()->new TimePickerDialog(activity,(view,h,m)->{
   time=LocalTime.of(h,m);
   timeButton.setText(timeLabel());
  },time.getHour(),time.getMinute(),android.text.format.DateFormat.is24HourFormat(activity)).show());
  form.addView(dateButton);
  form.addView(timeButton);
  days=new LinearLayout(activity);
  days.setOrientation(LinearLayout.VERTICAL);
  int mask=pair.optInt("scheduleDays",31);
  for(int index=0;index<7;index++){
   CheckBox day=new CheckBox(activity);
   day.setText(DayOfWeek.of(index+1).getDisplayName(TextStyle.FULL,Locale.getDefault()));
   day.setChecked((mask&(1<<index))!=0);
   weekdays[index]=day;
   days.addView(day);
  }
  form.addView(days);
  form.addView(SahlTheme.label(activity,"Android may start the backup after the selected time, depending on battery and network conditions.",14,SahlTheme.MUTED));
  int selection=pair.optInt("scheduleMode",pair.optBoolean("daily")?2:0);
  mode.setSelection(selection);
  updateVisibility(selection);
  mode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
   public void onItemSelected(AdapterView<?> parent,View view,int position,long id){updateVisibility(position);}
   public void onNothingSelected(AdapterView<?> parent){}
  });
 }

 private String dateLabel(){return "Date: "+date.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM));}
 private String timeLabel(){return "Time: "+time.format(DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT));}
 private void updateVisibility(int selected){
  dateButton.setVisibility(selected==1?View.VISIBLE:View.GONE);
  timeButton.setVisibility(selected>=1&&selected<=3?View.VISIBLE:View.GONE);
  days.setVisibility(selected==3?View.VISIBLE:View.GONE);
 }

 long apply(JSONObject pair)throws Exception{
  int selected=mode.getSelectedItemPosition();
  int mask=selected==2?127:0;
  if(selected==3){
   for(int index=0;index<7;index++)if(weekdays[index].isChecked())mask|=1<<index;
   if(mask==0)throw new IllegalArgumentException("Select at least one weekday.");
  }
  ZoneId zone=ZoneId.systemDefault();
  Instant now=Instant.now();
  long dateTime=date.atTime(time).atZone(zone).toInstant().toEpochMilli();
  if(selected==1&&dateTime<=now.toEpochMilli())
   throw new IllegalArgumentException("Pick a future date and time for the one-time backup.");
  long interval=selected==4?3600_000L:selected==5?6*3600_000L:0;
  long due=interval>0?now.toEpochMilli()+interval:selected==1?dateTime:mask==0?0:BackupSchedule.next(now,zone,time.getHour(),time.getMinute(),mask);
  pair.put("daily",false).put("scheduleMode",selected).put("scheduleDate",dateTime)
   .put("scheduleHour",time.getHour()).put("scheduleMinute",time.getMinute())
   .put("scheduleDays",mask).put("scheduleInterval",interval).put("scheduleGeneration",selected==0?"":UUID.randomUUID().toString());
  return due;
 }
}
