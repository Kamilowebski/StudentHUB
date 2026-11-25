'use strict';
const $ = id => document.getElementById(id);
const paths = {
 cap:'<path d="m2 10 10-5 10 5-10 5-10-5Zm4 2v5c4 3 8 3 12 0v-5m4-2v6"/>',
 home:'<path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z"/><path d="M9 21v-7h6v7"/>',
 calendar:'<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M16 3v4M8 3v4M3 10h18m-13 5h2m4 0h2"/>',
 book:'<path d="M12 5c-3-2-6-2-9-1v15c3-1 6-1 9 1 3-2 6-2 9-1V4c-3-1-6-1-9 1v15"/>',
 settings:'<circle cx="12" cy="12" r="3"/><path d="M10 3h4l.6 2.2 2 .9 2-.9 2.2 3.5-1.6 1.6v2.4l1.6 1.6-2.2 3.5-2-.9-2 .9L14 21h-4l-.6-2.2-2-.9-2 .9-2.2-3.5 1.6-1.6v-2.4L3.2 8.7l2.2-3.5 2 .9 2-.9z"/>',
 bell:'<path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9m-8 12a2 2 0 0 0 4 0"/>',
 check:'<rect x="5" y="4" width="14" height="17" rx="2"/><path d="M9 4V2h6v2m-6 9 2 2 4-4"/>',
 wallet:'<rect x="3" y="5" width="18" height="15" rx="2"/><path d="M21 10h-6a3 3 0 0 0 0 6h6m-5-3h.01"/>',
 mail:'<rect x="3" y="5" width="18" height="14" rx="2"/><path d="m4 7 8 6 8-6"/>',
 external:'<path d="M13 4h7v7m0-7-9 9m-2-8H5v14h14v-4"/>',
 plus:'<path d="M12 5v14M5 12h14"/>',
 clock:'<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
 pin:'<path d="M19 10c0 5-7 10-7 10S5 15 5 10a7 7 0 1 1 14 0Z"/><circle cx="12" cy="10" r="2"/>',
 close:'<path d="m6 6 12 12M18 6 6 18"/>',
 edit:'<path d="m14 6 4 4M4 20l4-.8L20 7l-4-4L4.8 15Z"/>'
};
const icon = name => '<svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round">'+paths[name]+'</svg>';
document.querySelectorAll('[data-icon]').forEach(el => {el.innerHTML = icon(el.dataset.icon);});
const pages = {
 start:['Start','home'],
 zajecia:['Zajęcia','book','Zajęcia','Przedmioty w aktywnym semestrze.','Dodaj przedmiot'],
 plan:['Plan','clock'],
 zjazdy:['Zjazdy','calendar','Plan zjazdów','Terminy zjazdów i informacje o miejscu.','Dodaj zjazd'],
 zaliczenia:['Zaliczenia','check','Zaliczenia','Terminy i wyniki zaliczeń.','Dodaj zaliczenie'],
 platnosci:['Płatności','wallet','Płatności','Kwoty, terminy i status opłat.','Dodaj płatność']
};
const localDay = date => [date.getFullYear(),date.getMonth()+1,date.getDate()].map((n,i)=>String(n).padStart(i?2:4,'0')).join('-');
function day(offset = 0) {
 const date = new Date();
 date.setDate(date.getDate()+offset);
 return localDay(date);
}
const creator = 'kamilsamiec@gmail.com';
const collections = ['zajecia','zjazdy','zaliczenia','platnosci'];
const scheduleDefaults={school:'',duration:'45',breakMinutes:'10',prepEnabled:'1',prepTime:'19:00',classAlerts:'1'};
let data = {scheduleSettings:scheduleDefaults, subjects:[], zajecia:[], zjazdy:[], zaliczenia:[], platnosci:[], semesters:['3'], active:'3', author:creator};
const semesterOf = item => item.semester || '3';
const records = key => data[key].filter(item=>semesterOf(item)===data.active);
const newId = () => Array.from(crypto.getRandomValues(new Uint8Array(16)), byte=>byte.toString(16).padStart(2,'0')).join('');
let toastTimer;
function notify(message) {
 $('toast').textContent = message;
 $('toast').hidden = false;
 clearTimeout(toastTimer);
 toastTimer = setTimeout(()=>{$('toast').hidden=true;},3200);
}
try {
 const saved = Android.getOrganizer();
 if(saved){
  const previous=JSON.parse(saved);
  data={...data,...previous,scheduleSettings:{...scheduleDefaults,...previous.scheduleSettings},author:creator};
  if(!previous.subjects){
   const byName=new Map();
   data.zajecia=data.zajecia.map(item=>{
    const semester=semesterOf(item);
    const key=JSON.stringify([semester,item.title,item.type]);
    if(!byName.has(key))byName.set(key,{id:newId(),semester,title:item.title,type:item.type});
    return {...item,subjectId:byName.get(key).id};
   });
   data.subjects=[...byName.values()];
  }
 }
} catch {
 notify('Zapis lokalny jest niedostępny lub nieczytelny.');
}
function save(next) {
 try {if(!Android.saveOrganizer(JSON.stringify(next))) throw new Error();}
 catch {notify('Nie udało się zapisać danych na urządzeniu.');return false;}
 data = next;
 return true;
}
const esc = value => String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const dateText = value => new Date(value+'T12:00').toLocaleDateString('pl-PL',{day:'numeric',month:'long',year:value.slice(0,4)===day().slice(0,4)?undefined:'numeric'});
const shortDay = value => value===day()?'Dzisiaj':value===day(1)?'Jutro':dateText(value);
const lessonStart = item => new Date(item.date+'T'+item.time).getTime();
function lessonInProgress(item,now){
 const start=lessonStart(item);
 const end=item.endTime?new Date(item.date+'T'+item.endTime).getTime():
  start+Number(data.scheduleSettings.duration)*60000;
 return start<=now&&now<end;
}
const upcoming = (now=Date.now()) => sorted('zajecia').filter(item=>lessonStart(item)>now);
function lessonPlace(item){
 if(item.room)return 'Sala '+item.room;
 return (item.trip&&records('zjazdy').find(trip=>trip.id===item.trip)?.room)||'';
}
function minutesUntil(item,now){
 const remaining=item?lessonStart(item)-now:0;
 return remaining>0&&remaining<=3600000?Math.ceil(remaining/60000):0;
}
let displayedLessonStatus;
function lessonStatus(now){
 const current=sorted('zajecia').filter(item=>lessonInProgress(item,now));
 const future=upcoming(now);
 return {current,future,signature:JSON.stringify([localDay(new Date(now)),current.map(item=>item.id),future[0]?.id,minutesUntil(future[0],now)])};
}
function sorted(key) {return records(key).sort((a,b)=>(a.date+(a.time||'')).localeCompare(b.date+(b.time||'')));}
function glyph(name,color='') {return '<span class="glyph '+color+'">'+icon(name)+'</span>';}
function heading(title,subtitle,key) {
 return '<div class="heading"><div><h1>'+title+'</h1><p>'+subtitle+'</p></div>'+(key?'<button class="primary" data-add="'+key+'">'+icon('plus')+(key==='subjects'?'Dodaj przedmiot':pages[key][4])+'</button>':'')+'</div>';
}
function card(key,item,compact=false,now=Date.now()) {
 const active=key==='zajecia'&&lessonInProgress(item,now);
 let title = item.title;
 let detail = shortDay(item.date);
 let end = '';
 let symbol = pages[key][1];
 let color = {zjazdy:'blue',zaliczenia:'green',platnosci:'orange'}[key]||'';
 if(key==='zajecia') {
  detail = [compact?'':detail,item.type,lessonPlace(item),item.teacher].filter(Boolean).join(' · ');
  end = item.time+(item.endTime?'–'+item.endTime:'');
  color = item.type==='Laboratorium'?'blue':item.type==='Ćwiczenia'?'green':'';
 } else if(key==='zjazdy') {
  title = dateText(item.date)+(item.endDate&&item.endDate!==item.date?' – '+dateText(item.endDate):'');
  detail = '';
 } else {
  detail += ' · '+item.status;
  end = key==='platnosci'?Number(item.amount).toLocaleString('pl-PL',{style:'currency',currency:'PLN'}):item.grade||'';
 }
 return '<button class="card'+(active?' lesson-live':'')+'" data-edit="'+key+'" data-id="'+esc(item.id)+'" aria-label="Edytuj '+esc(title)+(active?' · Trwa teraz':'')+'">'+glyph(symbol,color)+'<span class="card-copy"><strong>'+esc(title)+'</strong>'+(active?'<small class="lesson-live-label">Trwa teraz</small>':'')+(detail?'<small>'+esc(detail)+'</small>':'')+'</span><span class="end">'+esc(end)+'</span></button>';
}
const empty = text => '<p class="empty">'+text+'</p>';
function home() {
 const now=Date.now();
 const {current,future,signature}=lessonStatus(now);
 displayedLessonStatus=signature;
 const next=future[0];
 const place=next?lessonPlace(next):'';
 const minutes=minutesUntil(next,now);
 const daily=next?future.filter(item=>item.date===next.date):[];
 const tomorrow=day(1);
 const tomorrowLessons=sorted('zajecia').filter(item=>item.date===tomorrow);
 const ongoing=current.length?'<section class="ongoing-lessons"><div class="section-top"><h2>Trwa teraz</h2></div><div class="stack">'+
  current.map(item=>card('zajecia',item,true,now)).join('')+'</div></section>':'';
 const deadlines = [
  ['zaliczenia','Zaliczenie','Zaliczone','Brak nadchodzących zaliczeń.'],
  ['platnosci','Opłata','Opłacone','Brak nadchodzących opłat.']
 ].map(([key,label,completed,message])=>{
  const item=sorted(key).find(entry=>entry.status!==completed&&entry.date>=day());
  return '<section><div class="section-top"><h3>'+label+'</h3></div>'+(item?card(key,item):empty(message))+'</section>';
 }).join('');
 const hero = next?'<div class="hero-art"><span class="orbit"></span>'+icon('cap')+'</div><div class="hero-label">'+icon('calendar')+'Następne zajęcia</div><h2>'+esc(next.title)+'</h2><div class="hero-meta"><span>'+icon('clock')+esc(shortDay(next.date)+' · '+next.time+(next.endTime?'–'+next.endTime:''))+'</span>'+(place?'<span>'+icon('pin')+esc(place)+'</span>':'')+
 (minutes?'<span class="hero-countdown">Za '+minutes+' min</span>':'')+(next.teacher?'<span>'+icon('cap')+esc(next.teacher)+'</span>':'')+'</div><a class="hero-button" href="#plan">'+icon('calendar')+'Zobacz plan</a>':
 '<div class="hero-art">'+icon('cap')+'</div><div class="hero-label">'+icon('calendar')+'Następne zajęcia</div><h2>Brak nadchodzących zajęć</h2><button class="hero-button" data-add="subjects">'+icon('plus')+'Dodaj przedmiot</button>';
 const quick = [
  ['zjazdy','Zjazdy','Terminy zjazdów','blue'],['zaliczenia','Zaliczenia','Terminy i oceny','green'],
  ['platnosci','Płatności','Terminy opłat','orange']
 ].map(([key,title,copy,color])=>'<a class="tile" href="#'+key+'">'+glyph(pages[key][1],color)+'<strong>'+title+'</strong><small>'+copy+'</small></a>').join('');
 return heading('Twój pulpit','Plan i sprawy studenckie w jednym miejscu.')+
 ongoing+'<section class="hero">'+hero+'</section>'+
 (tomorrowLessons.length?'<section class="tomorrow-plan"><div class="section-top"><h2>Plan na jutro</h2><a href="#dzien/'+tomorrow+'">Otwórz plan</a></div>'+
 '<div class="stack">'+tomorrowLessons.map(item=>card('zajecia',item,true,now)).join('')+'</div></section>':'')+
 '<section class="deadlines"><div class="section-top"><h2>Najbliższe terminy</h2></div><div class="deadline-grid">'+deadlines+'</div></section><div class="dash-grid">'+(next?.date===tomorrow?'':'<section><div class="section-top"><h2>'+(next&&next.date!==tomorrow?'Plan zajęć · '+shortDay(next.date).toLowerCase():'Plan zajęć')+'</h2><a href="#plan">Cały plan</a></div><div class="stack">'+(daily.length?daily.slice(0,3).map(item=>card('zajecia',item,true)).join(''):empty('Dodaj zajęcia, aby zobaczyć je w planie.'))+'</div></section>')+'<section><div class="section-top"><h2>Szybki dostęp</h2></div><div class="quick">'+quick+'</div></section></div>';
}
const settingsSections = ['organizacja','import-planu','przypomnienia','kopia','o-aplikacji'];
const settingsBack = '<a class="settings-back" href="#ustawienia">Ustawienia</a>';
function reminders(settingsPanels) {
 const enabled=data.scheduleSettings.classAlerts==='1';
 const list=enabled?upcoming():[];
 return settingsBack+heading('Powiadomienia','Zarządzaj przypomnieniami i alertami poczty.')+
 '<div class="android-extra"><button class="text-button" data-native="notifications">Powiadomienia Androida</button>'+(Android.canScheduleExactly()?'':'<button class="text-button" data-native="exact-alarms">Zezwól na punktualne alerty</button>')+'</div>'+
 settingsPanels+(enabled?'<h2 class="settings-heading">Nadchodzące przypomnienia</h2><div class="stack list">'+
  (list.length?list.map(item=>card('zajecia',item)).join(''):empty('Brak nadchodzących lekcji.'))+'</div>':'');
}
const mainNav=[['start','Start','home'],['zjazdy','Plan','calendar'],['zaliczenia','Zaliczenia','check'],['platnosci','Płatności','wallet']];
const planSections = [['zjazdy','Zjazdy'],['plan','Lekcje'],['zajecia','Przedmioty']];
const swipeRoutes=mainNav.flatMap(([route])=>route==='zjazdy'?planSections.map(([id])=>id):[route]);
function planTabs(active){
 return '<nav class="plan-tabs" aria-label="Sekcje planu">'+planSections.map(([route,label])=>
  '<a href="#'+route+'"'+(active===route?' aria-current="page"':'')+'>'+label+'</a>').join('')+'</nav>';
}
function render() {
 const hash = location.hash.slice(1);
 const agendaDate=/^dzien\/(\d{4}-\d{2}-\d{2})$/.exec(hash)?.[1];
 const key = agendaDate?'plan':['poczta','semestry','ustawienia',...settingsSections].includes(hash)||Object.prototype.hasOwnProperty.call(pages,hash)?hash:'start';
 const activeNav=planSections.some(([route])=>route===key)?'zjazdy':key;
 $('nav').innerHTML=mainNav.map(([id,title,symbol])=>'<a class="nav-item" href="#'+id+'"'+(id===activeNav?' aria-current="page"':'')+'>'+icon(symbol)+title+'</a>').join('');
 $('date').textContent = new Date().toLocaleDateString('pl-PL',{weekday:'long',day:'numeric',month:'long'});
 $('semester-link').textContent='Semestr '+data.active;
 const settingsButton=$('settings-link');
 if(key==='ustawienia'||settingsSections.includes(key))settingsButton.setAttribute('aria-current','page');
 else settingsButton.removeAttribute('aria-current');
 if((key==='poczta'||key==='ustawienia'||settingsSections.includes(key)) && window.renderAndroid){window.renderAndroid(key);return;}
 if(key==='zajecia'){renderClasses();return;}
 if(key==='plan'){renderSchedule(agendaDate);return;}
 if(key==='zjazdy'){renderTrips();return;}
 if(key==='semestry'){renderSemesters();return;}
 const page = pages[key]||pages.start;
 const list=collections.includes(key)?sorted(key):[];
 $('view').innerHTML = !collections.includes(key)?home():
 heading(page[2],page[3],key)+'<div class="stack list">'+(list.length?list.map(item=>card(key,item)).join(''):empty('Nie ma jeszcze wpisów.'))+'</div>';
}
function weekendKey(day){
 const date=new Date(day+'T00:00:00Z');
 const weekday=date.getUTCDay();
 if(![0,5,6].includes(weekday))return null;
 date.setUTCDate(date.getUTCDate()-(weekday===0?2:weekday-5));
 return date.toISOString().slice(0,10);
}
function tripProposals(){
 const groups=new Map();
 function group(key){
  if(!groups.has(key))groups.set(key,{lessons:[],trips:[]});
  return groups.get(key);
 }
 for(const lesson of sorted('zajecia')){
  if(!lesson.trip)group(weekendKey(lesson.date)||lesson.date).lessons.push(lesson);
 }
 const trips=sorted('zjazdy');
 for(const trip of trips){
  const key=weekendKey(trip.date);
  if(key&&weekendKey(trip.endDate||trip.date)===key)group(key).trips.push(trip);
 }
 for(const [key,proposal] of groups){
  const {lessons,trips:included}=proposal;
  if(!lessons.length&&included.length<2){groups.delete(key);continue;}
  if(!included.length&&lessons.length){
   const first=lessons[0].date,last=lessons[lessons.length-1].date;
   const matches=trips.filter(trip=>trip.date<=first&&(trip.endDate||trip.date)>=last);
   if(matches.length===1)included.push(matches[0]);
  }
  const dates=[...lessons.map(item=>item.date),...included.flatMap(item=>[item.date,item.endDate||item.date])].sort();
  proposal.date=dates[0];
  const last=dates[dates.length-1];
  proposal.endDate=last===proposal.date?'':last;
 }
 return new Map([...groups].sort(([a],[b])=>a.localeCompare(b)));
}
function renderTrips(){
 const trips=sorted('zjazdy');
 const proposals=[...tripProposals()].map(([key,proposal])=>{
  const {date,endDate,lessons,trips}=proposal;
  const title=dateText(date)+(endDate?' – '+dateText(endDate):'');
  const action=trips.length>1?'Połącz zjazdy':trips.length?'Przypisz do zjazdu':'Sprawdź i zapisz';
  const description=trips.length>1?'Liczba zjazdów: '+trips.length:'Liczba lekcji: '+lessons.length;
  return '<button type="button" class="card" data-trip-proposal="'+esc(key)+'">'+glyph('calendar','blue')+
   '<span class="card-copy"><strong>'+esc(title)+'</strong><small>'+description+
   '</small></span><span class="end">'+action+'</span></button>';
 });
 $('view').innerHTML=planTabs('zjazdy')+heading('Zjazdy','Terminy zjazdów w aktywnym semestrze.','zjazdy')+
  '<div class="stack list">'+(trips.length?trips.map(item=>card('zjazdy',item)).join(''):empty('Nie ma jeszcze zapisanych zjazdów.'))+'</div>'+
  (proposals.length?'<section class="list class-section"><div class="section-top"><h2>Propozycje z planu</h2></div>'+
   '<div class="stack">'+proposals.join('')+'</div></section>':'');
}
function openTripProposal(key){
 const proposal=tripProposals().get(key);
 if(!proposal)return;
 const {date,endDate,lessons,trips}=proposal;
 const existing=trips[0];
 const sources=new Map(trips.map(trip=>[trip.id,trip]));
 const room=existing?existing.room:data.scheduleSettings.school;
 const linked=records('zajecia').filter(item=>sources.has(item.trip)).map(item=>{
  const oldRoom=sources.get(item.trip).room;
  return !item.room&&oldRoom&&oldRoom!==room?{...item,room:oldRoom}:item;
 });
 openEditor('zjazdy',existing?.id,{
  title:trips.length>1?'Połącz zjazdy':existing?'Przypisz lekcje do zjazdu':'Proponowany zjazd',
  trip:{...existing,date,endDate,room},
  lessons:[...linked,...lessons],
  fromPlan:true,
  mergedIds:trips.slice(1).map(trip=>trip.id)
 });
}
function renderClasses(){
 const subjects=records('subjects').sort((a,b)=>a.title.localeCompare(b.title,'pl'));
 $('view').innerHTML=planTabs('zajecia')+heading('Przedmioty','Dodaj przedmioty bez dat. Terminy ustawisz w zjazdach.','subjects')+
  '<section class="list class-section"><div class="stack">'+
  (subjects.length?subjects.map(subject=>'<button class="card" data-edit="subjects" data-id="'+esc(subject.id)+'">'+glyph('book')+
   '<span class="card-copy"><strong>'+esc(subject.title)+'</strong><small>'+esc([subject.type,subject.teacher].filter(Boolean).join(' · '))+'</small></span></button>').join(''):empty('Dodaj pierwszy przedmiot.'))+
  '</div></section>';
}
window.importSchedule=({events,skipped,semester})=>{
 if(semester&&semester!==data.active){
  notify('Zmieniono semestr. Wybierz plik ponownie.');return;
 }
 const subjects=[...data.subjects];
 const lessons=[...data.zajecia];
 const timeKey=item=>JSON.stringify([normalizeName(item.title),item.date,item.time]);
 const importedKey=item=>JSON.stringify([timeKey(item),normalizeName(item.room||'')]);
 const known=new Set(records('zajecia').map(importedKey));
 const emptyRooms=new Map();
 lessons.forEach((item,index)=>{
  if(semesterOf(item)!==data.active||item.room?.trim())return;
  const key=timeKey(item);
  emptyRooms.set(key,emptyRooms.has(key)?-1:index);
 });
 const offeredRooms=new Map();
 for(const event of events){
  if(!event.room)continue;
  const key=timeKey(event);
  if(!offeredRooms.has(key))offeredRooms.set(key,new Set());
  offeredRooms.get(key).add(normalizeName(event.room));
 }
 const catalog=new Map();
 for(const item of records('subjects')){
  const name=normalizeName(item.title);
  if(!catalog.has(name))catalog.set(name,item);
 }
 let added=0,updated=0;
 for(const event of events){
  const key=importedKey(event);
  if(known.has(key))continue;
  known.add(key);
  const time=timeKey(event);
  const position=emptyRooms.get(time);
  if(event.room&&position>=0&&offeredRooms.get(time)?.size===1){
   lessons[position]={...lessons[position],room:event.room};
   emptyRooms.delete(time);
   updated++;
   continue;
  }
  const name=normalizeName(event.title);
  let subject=catalog.get(name);
  if(!subject){
   subject={id:newId(),title:event.title,type:'Inne',semester:data.active};
   subjects.push(subject);
   catalog.set(name,subject);
  }
  lessons.push({...event,id:newId(),semester:data.active,subjectId:subject.id,title:subject.title,
   type:subject.type,teacher:subject.teacher||''});
  added++;
 }
 if((added||updated)&&!save({...data,subjects,zajecia:lessons}))return;
 render();
 notify('Dodano: '+added+' · Uzupełniono sale: '+updated+' · Duplikaty: '+(events.length-added-updated)+' · Pominięto: '+skipped);
};
function renderSchedule(date){
 const now=Date.now();
 displayedLessonStatus=lessonStatus(now).signature;
 const slots=sorted('zajecia').filter(item=>!date||item.date===date);
 $('view').innerHTML=planTabs('plan')+(date?heading(date===day(1)?'Plan na jutro':'Plan dnia',esc(dateText(date)))+
  '<div class="android-extra"><a class="text-button" href="#plan">Cały plan semestru</a></div>':
  heading('Lekcje','Daty, godziny i sale zaplanowanych lekcji.'))+
  '<section class="list"><div class="section-top"><h2>Zaplanowane lekcje</h2></div><div class="stack">'+
  (slots.length?slots.map(item=>card('zajecia',item,false,now)).join(''):empty(date?'Brak lekcji w tym dniu.':'Wybierz przedmioty w formularzu zjazdu, aby zaplanować lekcje.'))+'</div></section>';
}
function renderSemesters(){
 $('view').innerHTML=heading('Semestry','Wybierz osobny profil dla planu i spraw studenckich.')+
  '<div class="stack list">'+data.semesters.map(number=>'<button class="card" data-semester="'+number+'" aria-pressed="'+(number===data.active)+'">'+glyph('cap')+
   '<span class="card-copy"><strong>Semestr '+number+'</strong></span>'+(number===data.active?'<span class="end">Aktywny</span>':'')+'</button>').join('')+
  '<form id="semester-form" class="profile-form card"><label>Numer nowego semestru<input name="semester" type="number" min="1" max="30" step="1" required></label>'+
  '<button type="submit" class="primary">Dodaj semestr</button></form></div>';
 $('semester-form').onsubmit=event=>{
  event.preventDefault();
  const number=String(Number(new FormData(event.target).get('semester')));
  if(data.semesters.includes(number)){notify('Ten profil już istnieje.');return;}
  if(save({...data,semesters:[...data.semesters,number].sort((a,b)=>a-b),active:number})){render();notify('Dodano semestr '+number+'.');}
 };

}
const lessonTypes=['Wykład','Ćwiczenia','Laboratorium','Projekt','Seminarium','Inne'];
const fields = {
 subjects:[['title','Nazwa przedmiotu'],['type','Kategoria',lessonTypes],['teacher','Prowadzący','text',false]],
 zajecia:[['title','Nazwa zajęć'],['date','Data','date'],['time','Początek','time'],['endTime','Koniec','time',false],['room','Sala','text',false],['type','Rodzaj',['Wykład','Ćwiczenia','Laboratorium','Projekt','Seminarium','Inne']]],
 zjazdy:[['date','Pierwszy dzień','date'],['endDate','Ostatni dzień','date',false],['room','Miejsce','text',false]],
 zaliczenia:[['title','Nazwa zaliczenia'],['date','Termin','date'],['status','Status',['Do zaliczenia','Zaliczone','Do poprawy']],['grade','Ocena lub wynik','text',false]],
 platnosci:[['title','Nazwa opłaty'],['date','Termin','date'],['amount','Kwota w PLN','number'],['status','Status',['Do zapłaty','Opłacone']]]
};
let editKey, editId, editFromPlan, mergedTripIds;
const normalizeName = value => value.normalize('NFC').trim().replace(/\s+/g,' ').toLocaleLowerCase('pl-PL');
function teacherSuggestions(title,type,current,id) {
 const name=normalizeName(title);
 if(!name)return [];
 const matches=data.subjects.filter(item=>item.id!==id&&item.teacher?.trim()&&normalizeName(item.title)===name);
 const rank=item=>Number(item.type===type)*2+Number(semesterOf(item)===data.active);
 matches.sort((a,b)=>rank(b)-rank(a)||a.teacher.localeCompare(b.teacher,'pl'));
 const used=new Set([normalizeName(current)]);
 return matches.flatMap(item=>{
  const label=item.teacher.trim();
  const key=normalizeName(label);
  if(used.has(key))return [];
  used.add(key);
  return [label];
 });
}
function addTeacherSuggestions(id) {
 const form=$('form');
 const {title,type,teacher}=form.elements;
 $('fields').insertAdjacentHTML('beforeend','<div id="teacher-suggestions" hidden></div>');
 const panel=$('teacher-suggestions');
 function update(){
  const names=teacherSuggestions(title.value,type.value,teacher.value,id);
  panel.hidden=!names.length;
  panel.innerHTML=names.length?'<strong>Podpowiedzi prowadzących</strong><div class="teacher-options">'+names.map((name,index)=>
   '<button type="button" class="text-button" data-teacher-index="'+index+'">'+esc(name)+'</button>').join('')+'</div>':'';
  panel.querySelectorAll('button').forEach(button=>{
   button.onclick=()=>{
    teacher.value=names[Number(button.dataset.teacherIndex)];
    update();
    teacher.focus();
   };
  });
 }
 title.addEventListener('input',update);
 type.addEventListener('change',update);
 teacher.addEventListener('input',update);
 update();
}
function openEditor(key,id,draft) {
 editKey=key;editId=id;editFromPlan=Boolean(draft?.fromPlan);
 mergedTripIds=new Set(draft?.mergedIds||[]);
 clearCollision();
 const item=draft?.trip||records(key).find(record=>record.id===id)||{date:day(),room:key==='zjazdy'?data.scheduleSettings.school:''};
 if(key==='zajecia'&&item.trip){openEditor('zjazdy',item.trip);return;}
 $('form-title').textContent=draft?(draft.title||'Kopia zjazdu'):id?(key==='subjects'?'Edytuj przedmiot':'Edytuj wpis'):(key==='subjects'?'Dodaj przedmiot':pages[key][4]);
 $('remove').hidden=!id||editFromPlan;
 $('fields').innerHTML=fields[key].map(([name,label,type='text',required=true])=>{
  const value=item[name]??'';
  const fixed=key==='zajecia'&&item.subjectId&&['title','type'].includes(name)?' disabled':'';
  const control=typeof type==='object'?'<select name="'+name+'"'+fixed+'>'+(Array.isArray(type)?type.map(option=>[option,option]):Object.entries(type)).map(([key,copy])=>'<option value="'+esc(key)+'"'+(key===value?' selected':'')+'>'+esc(copy)+'</option>').join('')+'</select>':
   '<input name="'+name+'" type="'+type+'" value="'+esc(value)+'"'+(required?' required':'')+fixed+(type==='text'?' maxlength="120"':type==='number'?' min="0" step="0.01"':'')+'>';
  return '<label>'+label+control+'</label>';
 }).join('');
 if(key==='subjects')addTeacherSuggestions(id);
 if(key==='zjazdy'){
  addTripFields(id,draft?.lessons);
  if(id&&!editFromPlan){
   $('fields').insertAdjacentHTML('beforeend','<details class="trip-options"><summary>Opcje zjazdu</summary><button type="button" class="text-button" id="trip-copy">Kopiuj zjazd</button></details>');
   $('trip-copy').onclick=()=>openCopy(id);
  }
 }
 $('editor').showModal();
}
$('close').onclick=()=>$('editor').close();
$('remove').onclick=()=>{
 if(editKey==='subjects'&&data.zajecia.some(item=>item.subjectId===editId)){
  notify('Usuń najpierw zaplanowane lekcje tego przedmiotu.');return;
 }
 $('delete-title').textContent=editKey==='zjazdy'?'Usunąć zjazd i jego lekcje?':'Usunąć ten wpis?';
 $('delete-editor').showModal();
};
$('delete-cancel').onclick=()=>$('delete-editor').close();
$('delete-form').onsubmit=event=>{
 event.preventDefault();
 const next={...data,[editKey]:data[editKey].filter(item=>item.id!==editId)};
 if(editKey==='zjazdy') next.zajecia=data.zajecia.filter(item=>item.trip!==editId);
 if(save(next)){
  $('delete-editor').close();
  $('editor').close();
  render();
  notify(editKey==='zjazdy'?'Usunięto zjazd.':'Usunięto wpis.');
 }
};
$('form').addEventListener('input',()=>{clearCollision();$('form').querySelectorAll('input:invalid,select:invalid').forEach(field=>field.setCustomValidity(''));});
let tripLessons = [];
let lessonEditId;
function addTripFields(id,lessons) {
 tripLessons=lessons||(id?sorted('zajecia').filter(item=>item.trip===id):[]);
 $('fields').insertAdjacentHTML('beforeend','<fieldset class="trip-fields"><legend>Lekcje na zjeździe</legend><div id="lesson-slots"></div>'+
  (records('subjects').length?'<button type="button" class="text-button" id="add-slot">Dodaj lekcję</button>':
   '<p>Dodaj najpierw przedmiot w zakładce Zajęcia.</p>')+'</fieldset>');
 const add=$('add-slot');
 if(add)add.onclick=()=>openLesson();
 renderTripLessons();
}
function renderTripLessons() {
 const slots=$('lesson-slots');
 slots.innerHTML=[...tripLessons].sort((a,b)=>(a.date+a.time).localeCompare(b.date+b.time)).map(item=>
  '<button type="button" class="lesson-summary" data-id="'+esc(item.id)+'"><strong>'+esc(item.title)+'</strong><small>'+
  esc([dateText(item.date),item.time+(item.endTime?'–'+item.endTime:''),item.room?'Sala '+item.room:''].filter(Boolean).join(' · '))+
  '</small></button>').join('');
 slots.querySelectorAll('button').forEach(row=>{row.onclick=()=>openLesson(row.dataset.id);});
}
function addMinutes(time,minutes){
 if(!time)return '';
 const [hours,rest]=time.split(':').map(Number);
 const total=hours*60+rest+minutes;
 if(total>=24*60)return '';
 return String(Math.floor(total/60)).padStart(2,'0')+':'+String(total%60).padStart(2,'0');
}
function suggestedLesson(first,last){
 const duration=Number(data.scheduleSettings.duration);
 const pause=Number(data.scheduleSettings.breakMinutes);
 const previous=tripLessons.reduce((latest,item)=>{
  if(outsideTrip(item.date,first,last))return latest;
  const finish=item.endTime||addMinutes(item.time,duration);
  if(!finish)return latest;
  return !latest||item.date+finish>latest.date+latest.finish?{date:item.date,finish}:latest;
 },null);
 const time=previous?addMinutes(previous.finish,pause):'';
 const endTime=addMinutes(time,duration);
 return {date:previous?.date||first,time:endTime?time:'',endTime};
}
function openLesson(id) {
 const trip=$('form').elements;
 trip.date.setCustomValidity('');
 if(!trip.date.reportValidity()||!trip.endDate.reportValidity())return;
 lessonEditId=id;
 const item=tripLessons.find(lesson=>lesson.id===id)||suggestedLesson(trip.date.value,trip.endDate.value);
 $('lesson-title').textContent=id?'Edytuj lekcję':'Dodaj lekcję';
 $('lesson-remove').hidden=!id;
 const options='<option value="">Wybierz przedmiot</option>'+records('subjects').map(subject=>
  '<option value="'+esc(subject.id)+'" '+(subject.id===item.subjectId?'selected':'')+'>'+
  esc([subject.title,subject.type,subject.teacher].filter(Boolean).join(' · '))+'</option>').join('');
 $('lesson-fields').innerHTML='<label>Przedmiot<select name="subjectId" required>'+options+'</select></label><div class="trip-times">'+
  [['date','Data','date'],['time','Początek','time'],['endTime','Koniec','time'],['room','Sala','text']].map(([key,label,type])=>
   '<label>'+label+'<input name="'+key+'" type="'+type+'" value="'+esc(item[key])+'" maxlength="120" '+(key==='date'||key==='time'?'required':'')+'></label>').join('')+
  '</div>';
 const {time,endTime}=$('lesson-form').elements;
 const duration=Number(data.scheduleSettings.duration);
 let automatic=!endTime.value||endTime.value===addMinutes(time.value,duration);
 time.oninput=()=>{if(automatic)endTime.value=addMinutes(time.value,duration);};
 endTime.oninput=()=>{automatic=false;};
 $('lesson-editor').showModal();
}
$('lesson-close').onclick=()=>$('lesson-editor').close();
$('lesson-remove').onclick=()=>{
 $('form').elements.date.setCustomValidity('');
 clearCollision();
 tripLessons=tripLessons.filter(item=>item.id!==lessonEditId);
 $('lesson-editor').close();
 renderTripLessons();
 $('add-slot')?.focus();
};
$('lesson-form').addEventListener('input',()=>{
 $('lesson-form').querySelectorAll('input:invalid,select:invalid').forEach(field=>field.setCustomValidity(''));
});
function outsideTrip(date,first,last) {return date<first||date>(last||first);}
function fail(field,message){field.setCustomValidity(message);field.reportValidity();}
$('lesson-form').onsubmit=event=>{
 event.preventDefault();
 const form=event.target;
 const slot=Object.fromEntries(Array.from(new FormData(form),([name,value])=>[name,value.trim()]));
 const trip=$('form').elements;
 if(outsideTrip(slot.date,trip.date.value,trip.endDate.value))return fail(form.elements.date,'Wybierz dzień przypadający w terminie zjazdu.');
 if(slot.endTime&&slot.endTime<=slot.time)return fail(form.elements.endTime,'Wybierz godzinę późniejszą niż początek.');
 const subject=records('subjects').find(item=>item.id===slot.subjectId);
 if(!subject)return fail(form.elements.subjectId,'Wybierz przedmiot.');
 const id=lessonEditId||newId();
 trip.date.setCustomValidity('');
 clearCollision();
 tripLessons=[...tripLessons.filter(item=>item.id!==id),{...slot,id,semester:data.active,title:subject.title,type:subject.type,teacher:subject.teacher||''}];
 $('lesson-editor').close();
 renderTripLessons();
 Array.from($('lesson-slots').querySelectorAll('button')).find(row=>row.dataset.id===id)?.focus();
};
let copySource;
function openCopy(id){
 copySource=records('zjazdy').find(item=>item.id===id);
 if(!copySource)return;
 $('copy-form').reset();
 $('copy-form').elements.date.setCustomValidity('');
 $('copy-editor').showModal();
}
$('copy-form').addEventListener('input',event=>event.target.setCustomValidity(''));
$('copy-close').onclick=()=>$('copy-editor').close();
$('copy-form').onsubmit=event=>{
 event.preventDefault();
 const date=event.target.elements.date.value;
 const utc=value=>Date.parse(value+'T00:00:00Z');
 const delta=utc(date)-utc(copySource.date);
 const shifted=value=>new Date(utc(value)+delta).toISOString().slice(0,10);
 const endDate=copySource.endDate?shifted(copySource.endDate):'';
 if(endDate&&!/^\d{4}-\d{2}-\d{2}$/.test(endDate))return fail(event.target.elements.date,'Wybierz wcześniejszą datę.');
 const lessons=records('zajecia').filter(item=>item.trip===copySource.id).map(item=>
  ({...item,id:newId(),date:shifted(item.date)}));
 $('copy-editor').close();
 if($('editor').open)$('editor').close();
 openEditor('zjazdy',undefined,{trip:{date,endDate,room:copySource.room},lessons});
};
function clearCollision(){
 $('collision-warning').hidden=true;
 $('save-with-collision').hidden=true;
}
function overlaps(a,b){
 if(a.date!==b.date)return false;
 if(a.time===b.time)return true;
 return a.time<b.time?Boolean(a.endTime&&b.time<a.endTime):Boolean(b.endTime&&a.time<b.endTime);
}
function findCollision(changed,other){
 for(let i=0;i<changed.length;i++){
  const item=changed[i];
  const match=changed.slice(i+1).find(candidate=>overlaps(item,candidate))||other.find(candidate=>overlaps(item,candidate));
  if(match)return [item,match];
 }
 return null;
}
function showCollision([a,b]){
 const warning=$('collision-warning');
 warning.textContent='Kolizja '+dateText(a.date)+': '+a.title+' ('+a.time+') i '+b.title+' ('+b.time+').';
 warning.hidden=false;
 $('save-with-collision').hidden=false;
 warning.scrollIntoView({block:'nearest'});
}
$('form').onsubmit=event=>{
 event.preventDefault();
 const form=event.target;
 const values=Object.fromEntries(Array.from(new FormData(form),([name,value])=>[name,value.trim()]));
 if(form.elements.title&&!form.elements.title.disabled&&!values.title) return fail(form.elements.title,'Wpisz nazwę.');
 if(values.endTime&&values.endTime<=values.time) return fail(form.elements.endTime,'Wybierz godzinę późniejszą niż początek.');
 if(values.endDate&&values.endDate<values.date) return fail(form.elements.endDate,'Wybierz dzień równy dacie rozpoczęcia lub późniejszy.');
 const previous=records(editKey).find(item=>item.id===editId);
 const record={...previous,...values,id:editId||newId(),semester:data.active};
 const next={...data,[editKey]:[...data[editKey].filter(item=>item.id!==editId),record]};
 let collision;
 if(editKey==='subjects'){
  next.zajecia=data.zajecia.map(item=>item.subjectId===record.id?{...item,title:record.title,type:record.type,teacher:record.teacher}:item);
 }
 if(editKey==='zajecia'&&record.subjectId){
  const subject=records('subjects').find(item=>item.id===record.subjectId);
  record.title=subject.title;
  record.type=subject.type;
  record.teacher=subject.teacher||'';
 }
 if(editKey==='zajecia')collision=findCollision([record],records('zajecia').filter(item=>item.id!==editId));
 if(editKey==='zjazdy'){
  if(tripLessons.some(slot=>outsideTrip(slot.date,record.date,record.endDate))){
   return fail(form.elements.date,'Co najmniej jedna lekcja wypada poza terminem zjazdu. Zmień datę lekcji lub zakres zjazdu.');
  }
  const selectedIds=new Set(tripLessons.map(slot=>slot.id));
  const replaced=item=>(editId&&item.trip===editId)||mergedTripIds.has(item.trip);
  const remains=item=>!selectedIds.has(item.id)&&(editFromPlan||!replaced(item));
  collision=findCollision(tripLessons,records('zajecia').filter(remains));
  next.zjazdy=next.zjazdy.filter(trip=>!mergedTripIds.has(trip.id));
  next.zajecia=[...data.zajecia.filter(remains).map(item=>replaced(item)?{...item,trip:''}:item),
   ...tripLessons.map(slot=>({...slot,trip:record.id}))];
 }
 if(collision&&(event.submitter!==$('save-with-collision')||$('save-with-collision').hidden)){
  showCollision(collision);return;
 }
 if(save(next)){$('editor').close();render();notify('Zapisano wpis.');}
};
$('view').addEventListener('click',event=>{
 const button=event.target.closest('button');
 if(!button) return;
 if(button.hasAttribute('data-import-usos'))Android.importCalendarUrl(data.active);
 if(button.hasAttribute('data-import-ics'))Android.importCalendarFile(data.active);
 const {add,edit,id,semester,tripProposal}=button.dataset;
 if(tripProposal)openTripProposal(tripProposal);
 if(semester&&save({...data,active:semester})){render();notify('Wybrano semestr '+semester+'.');}
 if(add) openEditor(add);
 if(edit) openEditor(edit,id);

});
const mobileLayout=window.matchMedia('(max-width:650px)');
function pageScrollY(){return mobileLayout.matches?$('main-content').scrollTop:window.scrollY;}
function scrollPageTop(){
 if(mobileLayout.matches)$('main-content').scrollTop=0;
 else window.scrollTo(0,0);
}
window.addEventListener('hashchange',()=>{render();scrollPageTop();});
render();

function refreshLessonStatus(){
 const route=location.hash;
 if(document.hidden||!['','#start','#plan'].includes(route)||document.querySelector('dialog[open]'))return;
 if(lessonStatus(Date.now()).signature!==displayedLessonStatus)render();
}
setInterval(refreshLessonStatus,10000);
document.addEventListener('visibilitychange',refreshLessonStatus);
