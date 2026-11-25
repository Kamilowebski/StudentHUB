'use strict';

let mailState = {connected:false, loading:false, messages:[], message:null, composing:false};
let mailAlerts = Android.mailAlertsEnabled();
let unreadOnly = false;
let selecting = false;
const selected = new Set();
let attachmentName = '';
window.refreshMailSettings=()=>{if(location.hash==='#przypomnienia')render();};
const isMail = () => location.hash === '#poczta';
window.studenthubBack = () => {
 if($('delete-editor').open){$('delete-editor').close();return true;}
 if($('copy-editor').open){$('copy-editor').close();return true;}
 if($('lesson-editor').open){$('lesson-editor').close();return true;}
 if($('editor').open){$('editor').close();return true;}
 if(!isMail())return false;
 if(mailState.message){
  mailState.message=null;
  render();
  return true;
 }
 if(selecting){selecting=false;selected.clear();render();return true;}
 return false;
};
const button = (action,label,style='text-button') => '<button class="'+style+'" data-native="'+action+'">'+label+'</button>';
window.renderAndroid = key => {
 const view = $('view');
 if(key==='ustawienia'){
  const entries=[
   ['organizacja','calendar','Szkoła i godziny','Domyślne miejsce zjazdu, długość lekcji i przerwy'],
   ['przypomnienia','bell','Powiadomienia','Przypomnienia, alerty poczty i ustawienia Androida'],
   ['import-planu','calendar','Import planu','Link z USOS lub plik iCalendar'],
   ['kopia','check','Kopia zapasowa','Zapis i przywracanie danych'],
   ['o-aplikacji','cap','O aplikacji','Wersja i twórca']
  ];
  view.innerHTML=heading('Ustawienia','')+'<div class="stack list">'+entries.map(([route,symbol,title,description])=>
   '<a class="card" href="#'+route+'">'+glyph(symbol)+'<span class="card-copy"><strong>'+title+'</strong><small>'+description+'</small></span></a>').join('')+'</div>';
  return;
 }
 if(key==='import-planu'){
  view.innerHTML=settingsBack+heading('Import planu','Terminy zostaną dodane do aktywnego semestru.')+
   '<div class="android-extra"><button type="button" class="text-button" data-import-usos>Importuj z USOS</button><button type="button" class="text-button" data-import-ics>Importuj plik ICS</button></div>';
  return;
 }
 if(key==='organizacja'){
  const {school,duration,breakMinutes}=data.scheduleSettings;
  view.innerHTML=settingsBack+heading('Szkoła i godziny','Domyślne wartości dla nowych zjazdów i lekcji.')+
   '<form id="schedule-settings" class="card app-info list"><label>Nazwa szkoły<input name="school" maxlength="120" value="'+esc(school)+'"></label>'+
   '<label>Długość lekcji (min)<input name="duration" type="number" min="1" max="1439" step="1" required value="'+esc(duration)+'"></label>'+
   '<label>Długość przerwy (min)<input name="breakMinutes" type="number" min="0" max="1439" step="1" required value="'+esc(breakMinutes)+'"></label>'+
   '<div><button type="submit" class="primary">Zapisz</button></div></form>';
  $('schedule-settings').onsubmit=event=>{
   event.preventDefault();
   const fields=new FormData(event.target);
   const school=fields.get('school').trim();
   const duration=String(Number(fields.get('duration')));
   const breakMinutes=String(Number(fields.get('breakMinutes')));
   if(save({...data,scheduleSettings:{...data.scheduleSettings,school,duration,breakMinutes}}))notify('Zapisano ustawienia.');
  };
  return;
 }
 if(key==='przypomnienia'){
  const action=mailAlerts?button('alerts-off','Wyłącz alerty poczty'):
   mailState.connected?button('alerts-on','Włącz alerty poczty'):
   '<a class="text-button" href="#poczta">Połącz pocztę</a>';
  const blocked=mailAlerts&&!Android.mailNotificationsAllowed();
  const settings=data.scheduleSettings;
  const classAlerts='<section class="card app-info list prep-settings"><h2>Przypomnienia o lekcjach</h2>'+
   '<label class="checkline"><input type="checkbox" id="class-alerts" '+(settings.classAlerts==='1'?'checked':'')+'>Włącz dla wszystkich lekcji</label>'+
   '<p>30 minut przed każdą lekcją w aktywnym semestrze.</p></section>';
  const prep='<form id="prep-settings" class="card app-info list prep-settings"><h2>Przygotowanie na jutro</h2>'+
   '<label class="checkline"><input type="checkbox" name="enabled" '+(settings.prepEnabled==='1'?'checked':'')+'>Przypomnij dzień przed zajęciami</label>'+
   '<label>Godzina przypomnienia<input type="time" name="time" value="'+esc(settings.prepTime)+'" required></label>'+
   '<div><button type="submit" class="text-button">Zapisz</button></div></form>';
  view.innerHTML=reminders(classAlerts+prep+'<section class="card app-info list"><h2>Poczta uczelniana</h2><p>'+
   (blocked?'Android blokuje powiadomienia o poczcie.':mailAlerts?'Sprawdzanie nowych wiadomości w tle jest włączone.':'Sprawdzanie nowych wiadomości w tle jest wyłączone.')+
   '</p><div class="android-extra">'+action+(mailAlerts?button('mail-notification-settings','Ustawienia powiadomień poczty'):'')+'</div></section>');
  $('class-alerts').onchange=event=>{
   const checked=event.target.checked;
   if(save({...data,scheduleSettings:{...data.scheduleSettings,classAlerts:checked?'1':'0'}})){
    render();
    $('class-alerts').focus();
    notify(checked?'Włączono przypomnienia o lekcjach.':'Wyłączono przypomnienia o lekcjach.');
   }else event.target.checked=!checked;
  };
  $('prep-settings').onsubmit=event=>{
   event.preventDefault();
   const fields=event.target.elements;
   const scheduleSettings={...data.scheduleSettings,prepEnabled:fields.enabled.checked?'1':'0',prepTime:fields.time.value};
   if(save({...data,scheduleSettings}))notify('Zapisano przypomnienie.');
  };
  return;
 }
 if(key==='o-aplikacji'){
  view.innerHTML=settingsBack+heading('O aplikacji','')+
   '<section class="card app-info list"><p>StudentHUB · wersja '+esc(Android.version())+
   '</p><p>Twórca <strong>'+esc(creator)+'</strong></p></section>';
  return;
 }
 if(key==='kopia'){
  view.innerHTML=settingsBack+heading('Kopia zapasowa','Kopia semestrów, planów, zaliczeń i płatności.')+
   '<p class="notice">Automatyczną kopię na koncie Google włączysz w ustawieniach Androida. Możesz też zapisać lub przywrócić plik kopii i wybrać Dysk Google w systemowym oknie plików, jeśli Twój telefon go udostępnia.</p>'+
   '<div class="android-extra">'+button('backup-settings','Ustawienia kopii')+button('export','Zapisz kopię','primary')+button('import','Przywróć kopię')+'</div>';
  return;
 }
 const state=mailState;
 let content=heading('Poczta uczelniana','Skrzynka studencka Politechniki Świętokrzyskiej.');
 if(!state.connected && !state.loading){
  view.innerHTML=content+'<div class="notice">Połącz konto studenckie, aby odbierać i wysyłać wiadomości w StudentHUB.</div>'+button('login','Połącz konto','primary');
  return;
 }
 if(state.loading){
  view.innerHTML=content+empty('Łączenie ze skrzynką…');
  return;
 }
 if(state.composing){
  view.innerHTML=content+'<div class="android-extra">'+button('inbox','Skrzynka odbiorcza')+'</div><form id="compose" class="compose list"><label>Do<input type="email" name="recipient" required></label><label>Temat<input name="subject" maxlength="240"></label><label>Wiadomość<textarea name="body" required maxlength="30000"></textarea></label><div class="compose-attachment"><button type="button" class="text-button" data-native="pick-attachment">Dołącz plik</button><span id="attachment-name"></span><button type="button" class="text-button" data-native="clear-attachment" id="clear-attachment" hidden>Usuń plik</button></div><button type="submit" class="primary">Wyślij</button></form>';
  updateAttachment();
  $('compose').onsubmit=event=>{
   event.preventDefault();
   const form=event.target;
   const values=new FormData(form);
   form.querySelector('[type=submit]').disabled=true;
   Android.send(values.get('recipient'),values.get('subject'),values.get('body'));
  };
  return;
 }
 if(state.message){
  const message=state.message;
  view.innerHTML=heading(esc(message.subject||'(Bez tematu)'),esc(message.from))+
    '<div class="android-extra">'+button('inbox','Skrzynka odbiorcza')+button(message.read?'unread-current':'read-current',message.read?'Oznacz jako nieprzeczytaną':'Oznacz jako przeczytaną')+'</div><p class="detail-label">'+esc(message.date)+'</p><article class="card message-body">'+esc(message.body)+'</article>'+attachmentList(message);
  return;
 }
 const shown=state.messages.filter(message=>!unreadOnly||!message.read);
 const checked=shown.filter(message=>selected.has(message.uid)).length;
 content+='<div class="mail-toolbar">'+button('compose','Napisz','primary')+
  '<details class="mail-menu"><summary>Opcje</summary><div class="mail-menu-items">'+button('refresh','Odśwież')+
  button('unread-filter',unreadOnly?'Pokaż wszystkie':'Tylko nieprzeczytane')+
  button('selection-mode',selecting?'Zakończ zaznaczanie':'Zaznacz wiadomości')+
  button('logout','Rozłącz konto')+'</div></details></div>'+
  (unreadOnly?'<p class="mail-filter">Tylko nieprzeczytane</p>':'')+
  (selecting?'<div class="android-extra" aria-label="Zaznaczanie wiadomości">'+
    button('selection-mode','Gotowe')+(shown.length?button('select-all',checked===shown.length?'Odznacz wszystkie':'Zaznacz wszystkie'):'')+
    (selected.size?button('read-selected','Oznacz jako przeczytane')+button('unread-selected','Oznacz jako nieprzeczytane'):'')+'</div>':'');
 view.innerHTML=content+'<section class="mail-list list" aria-label="Skrzynka odbiorcza">'+
  (shown.length?shown.map(message=>{
   const title=esc(message.subject||'(Bez tematu)');
   return '<div class="mail-row'+(message.read?'':' unread')+'">'+(selecting?'<label class="mail-select"><input type="checkbox" data-mail-select="'+esc(message.uid)+'" aria-label="Zaznacz: '+title+'" '+(selected.has(message.uid)?'checked':'')+'></label>':'')+
    '<button class="mail-item" data-native="read" data-uid="'+esc(message.uid)+'">'+
    (message.read?'':'<span class="mail-dot" aria-label="Nieprzeczytana"></span>')+
    '<span class="mail-lines"><strong>'+title+'</strong><small>'+esc(message.from||'')+'</small></span>'+
    '<time>'+esc(message.date||'')+'</time></button></div>';
  }).join(''):empty(unreadOnly?'Brak nieprzeczytanych wiadomości.':'Skrzynka odbiorcza jest pusta.'))+'</section>';

};

function attachmentList(message){
 const files=message.attachments||[];
 if(!files.length)return '';
 return '<section class="list mail-attachments"><div class="section-top"><h2>Załączniki</h2></div><div class="stack">'+files.map((name,index)=>
  '<button type="button" class="card" data-native="save-attachment" data-index="'+index+'">'+glyph('book')+
  '<span class="card-copy"><strong>'+esc(name)+'</strong></span><span class="end">Zapisz</span></button>').join('')+'</div></section>';
}
function updateAttachment(){
 const name=$('attachment-name');
 if(!name)return;
 name.textContent=attachmentName;
 $('clear-attachment').hidden=!attachmentName;
}
function clearAttachment(){
 attachmentName='';
 Android.clearAttachment();
 updateAttachment();
}
const swipeView=$('view');
const reducedMotion=window.matchMedia('(prefers-reduced-motion: reduce)');
let gestureStart,swipeAnimation;
const moveX=x=>'translate3d('+x+'px,0,0)';
function swipeDestination(route,dx){
 return swipeRoutes[swipeRoutes.indexOf(route)+(dx<0?1:-1)];
}
function cancelSwipeAnimation(){
 if(!swipeAnimation)return;
 swipeAnimation();
 swipeAnimation=null;
}
function settleSwipe(offset){
 swipeView.style.transform='';
 if(!offset||reducedMotion.matches||!swipeView.animate)return;
 const animation=swipeView.animate([{transform:moveX(offset)},{transform:moveX(0)}],
  {duration:170,easing:'cubic-bezier(.2,.75,.25,1)'});
 swipeAnimation=()=>animation.cancel();
}
function changeBySwipe(destination,offset,direction){
 if(reducedMotion.matches||!swipeView.animate){location.hash='#'+destination;return;}
 swipeView.style.transform='';
 const rect=swipeView.getBoundingClientRect();
 const outgoing=swipeView.cloneNode(true);
 outgoing.removeAttribute('id');
 outgoing.querySelectorAll('[id]').forEach(element=>element.removeAttribute('id'));
 outgoing.classList.add('swipe-snapshot');
 outgoing.setAttribute('aria-hidden','true');
 outgoing.inert=true;
 Object.assign(outgoing.style,{left:rect.left+'px',top:rect.top+'px',width:rect.width+'px'});
 document.body.append(outgoing);
 try {
  history.pushState(null,'','#'+destination);
  render();
  scrollPageTop();
 } catch (error) {
  outgoing.remove();
  location.hash='#'+destination;
  return;
 }
 const distance=window.innerWidth+Math.abs(rect.left);
 const settings={duration:235,easing:'cubic-bezier(.18,.72,.25,1)'};
 const before=outgoing.animate([{transform:moveX(offset)},{transform:moveX(direction*distance)}],settings);
 const after=swipeView.animate([{transform:moveX(offset-direction*distance)},{transform:moveX(0)}],settings);
 const cleanup=()=>{before.cancel();after.cancel();outgoing.remove();};
 swipeAnimation=cleanup;
 after.onfinish=()=>{
  if(swipeAnimation!==cleanup)return;
  cancelSwipeAnimation();
 };
}
swipeView.addEventListener('touchstart',event=>{
 gestureStart=null;
 if(event.touches.length!==1||event.target.closest('input,textarea,select,summary'))return;
 if(swipeAnimation)cancelSwipeAnimation();
 const route=location.hash.slice(1);
 const swipeRoute=route.startsWith('dzien/')?'plan':route||'start';
 const touch=event.touches[0];
 const mail=isMail()&&mailState.connected&&!mailState.loading&&!mailState.message&&!mailState.composing&&!selecting&&pageScrollY()<=0;
 const navigation=swipeRoutes.includes(swipeRoute)&&touch.clientX>24&&touch.clientX<window.innerWidth-24;
 if(mail||navigation)gestureStart={x:touch.clientX,y:touch.clientY,route,swipeRoute,mail,axis:'',offset:0};
},{passive:true});
swipeView.addEventListener('touchmove',event=>{
 const start=gestureStart;
 if(!start||event.touches.length!==1)return;
 const touch=event.touches[0];
 const dx=touch.clientX-start.x,dy=touch.clientY-start.y;
 if(!start.axis&&Math.max(Math.abs(dx),Math.abs(dy))>=8)start.axis=Math.abs(dx)>Math.abs(dy)?'x':'y';
 if(start.mail){
  if(start.axis==='y'&&dy>0&&event.cancelable)event.preventDefault();
  return;
 }
 if(start.axis!=='x')return;
 if(event.cancelable)event.preventDefault();
 if(reducedMotion.matches)return;
 start.offset=swipeDestination(start.swipeRoute,dx)?dx:dx*.24;
 swipeView.style.transform=moveX(start.offset);
},{passive:false});
swipeView.addEventListener('touchcancel',()=>{
 if(gestureStart)settleSwipe(gestureStart.offset);
 gestureStart=null;
},{passive:true});
swipeView.addEventListener('touchend',event=>{
 const start=gestureStart;
 gestureStart=null;
 if(!start)return;
 if(event.touches.length||event.changedTouches.length!==1||location.hash.slice(1)!==start.route){
  settleSwipe(start.offset);
  return;
 }
 const touch=event.changedTouches[0];
 const dx=touch.clientX-start.x,dy=touch.clientY-start.y;
 if(start.mail){
  if(dy>=70&&dy>Math.abs(dx)*1.5&&!mailState.loading)Android.refresh();
  return;
 }
 const destination=swipeDestination(start.swipeRoute,dx);
 if(start.axis==='y'||Math.abs(dx)<65||Math.abs(dx)<Math.abs(dy)*1.5||!destination){
  settleSwipe(start.offset);
  return;
 }
 changeBySwipe(destination,start.offset,Math.sign(dx));
},{passive:true});
window.addEventListener('hashchange',()=>{
 cancelSwipeAnimation();
 swipeView.style.transform='';
});
$('view').addEventListener('change',event=>{
 const uid=event.target.dataset.mailSelect;
 if(!uid)return;
 if(event.target.checked)selected.add(uid);
 else selected.delete(uid);
 render();
 Array.from($('view').querySelectorAll('[data-mail-select]')).find(el=>el.dataset.mailSelect===uid)?.focus();
});
$('view').addEventListener('click',event=>{
 const target=event.target.closest('[data-native]');
 if(!target) return;
 switch(target.dataset.native){
  case 'exact-alarms': Android.exactAlarms();break;
  case 'mail-notification-settings': Android.mailNotificationSettings();break;
  case 'notifications': Android.notifications(); break;
  case 'backup-settings': Android.backupSettings(); break;
  case 'export': Android.exportBackup(); break;
  case 'import': Android.importBackup(); break;
  case 'pick-attachment': Android.pickAttachment();break;
  case 'clear-attachment': clearAttachment();break;
  case 'save-attachment': {
   const message=mailState.message;
   const index=Number(target.dataset.index);
   const filename=message?.attachments?.[index];
   if(filename)Android.saveMailAttachment(message.uid,index,filename);
   break;
  }
  case 'login': Android.login(); break;
  case 'logout': Android.logout(); break;
  case 'refresh': Android.refresh(); break;
  case 'alerts-on': Android.mailAlerts(true);break;
  case 'alerts-off': Android.mailAlerts(false);break;
  case 'selection-mode': selecting=!selecting;selected.clear();render();break;
  case 'unread-filter': unreadOnly=!unreadOnly;selected.clear();render();break;
  case 'select-all': {
   const keys=mailState.messages.filter(message=>!unreadOnly||!message.read).map(message=>message.uid);
   if(keys.every(uid=>selected.has(uid))) keys.forEach(uid=>selected.delete(uid));
   else keys.forEach(uid=>selected.add(uid));
   render();break;
  }
  case 'read-selected':
  case 'unread-selected':
   Android.markMail(JSON.stringify([...selected]),target.dataset.native==='read-selected');break;
  case 'read-current':
  case 'unread-current':
   if(mailState.message) Android.markMail(JSON.stringify([mailState.message.uid]),target.dataset.native==='read-current');
   break;
  case 'read':
   if(selecting){
    const uid=target.dataset.uid;
    if(selected.has(uid))selected.delete(uid);
    else selected.add(uid);
    render();
   }else Android.read(target.dataset.uid);
   break;
  case 'compose': clearAttachment();mailState.composing=true;render();break;
  case 'inbox': if(mailState.composing)clearAttachment();mailState.composing=false;mailState.message=null;render();break;
 }
});
window.nativeMail = (event,payload) => {
 switch(event){
  case 'attachment':
   if(mailState.composing){attachmentName=payload;updateAttachment();}
   else Android.clearAttachment();
   return;
  case 'loading': mailState.loading=true;break;
  case 'inbox':
   mailState={connected:true,loading:false,messages:payload,message:null,composing:false};
   selected.clear();break;
  case 'alerts': mailAlerts=payload;break;
  case 'marked':
   mailState.messages.forEach(message=>{if(payload.keys.includes(message.uid))message.read=payload.read;});
   if(mailState.message&&payload.keys.includes(mailState.message.uid))mailState.message.read=payload.read;
   selected.clear();break;
  case 'message':
   mailState.message=payload;
   mailState.messages.forEach(message=>{if(message.uid===payload.uid)message.read=payload.read;});
   break;
  case 'sent': attachmentName='';mailState.composing=false;notify('Wysłano wiadomość.');break;
  case 'logout':
   mailState={connected:false,loading:false,messages:[],message:null,composing:false};
   mailAlerts=false;selecting=false;unreadOnly=false;selected.clear();break;
  case 'error':
   mailState.loading=false;
   notify(payload);
   if(mailState.composing){const submit=$('compose')?.querySelector('[type=submit]');if(submit)submit.disabled=false;return;}
   break;
 }
 if(isMail()||location.hash==='#przypomnienia') render();
};
render();

Android.resumeMail();
