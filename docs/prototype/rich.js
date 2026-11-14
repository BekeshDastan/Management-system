/* Presentation and small daily interactions for the disposable UX prototype. */
function foodPhoto(tile=0,className='',label='Фотография еды для оформления'){
 return `<span class="food-photo photo-tile tile-${Number.isInteger(tile)&&tile>=0&&tile<12?tile:0} ${className}" role="img" aria-label="${esc(label)}"></span>`;
}
function welcomePhoto(){return `<div class="hero-photo welcome-photo">${foodPhoto(0,'','Йогурт с ягодами — иллюстрация')}<div class="hero-caption"><span class="pill">Маленькие шаги · большие истории</span></div></div>`;}
function homeHero(){return `<div class="hero-photo">${foodPhoto((calendarDay(state.date)%6+6)%6,'','Еда — атмосферная иллюстрация')}<div class="hero-caption"><span class="eyebrow">Место для себя</span><h2>Каждый день —<br>новая страница.</h2></div></div>`;}
function dayStrip(){
 const end=calendarDay(state.date),today=calendarDay(TODAY);
 return `<div class="day-strip" aria-label="Даты дневника">${Array.from({length:7},(_,i)=>{const date=dateFromDay(end-6+i),d=state.days[date],valid=date>=MIN_DATE&&date<=TODAY;return `<button class="day-chip" data-action="rich-date" data-date="${date}" aria-pressed="${date===state.date}" ${valid?'':'disabled'}><small>${new Intl.DateTimeFormat('ru-RU',{weekday:'short',timeZone:'UTC'}).format(new Date((end-6+i)*DAY_MS))}</small><b>${Number(date.slice(-2))}</b><span aria-label="${d?.complete?'Питание заполнено':d?.entries?.length?'Есть записи':'Нет записей'}">${d?.complete?'●':d?.entries?.length?'◐':'·'}</span></button>`;}).join('')}</div>`;
}
function homeShortcuts(){return `<div class="home-shortcuts"><button class="mini-feature" data-action="calendar"><span class="feature-icon" aria-hidden="true">▦</span>Календарь</button><button class="mini-feature" data-action="report"><span class="feature-icon" aria-hidden="true">↗</span>Отчёты</button><button class="mini-feature" data-action="tools"><span class="feature-icon" aria-hidden="true">⊞</span>Ещё</button></div>`;}

function toolsView(){return `<div class="screen">${header('home','Для вашего ритма','Всё нужное — под рукой.')}<div class="tools-grid">${[['calendar','▦','Календарь'],['report','↗','Отчёты'],['progress','⌁','Подробные графики'],['photo-feed','▧','Фотолента'],['diary-search','⌕','Поиск по дневнику'],['templates','▤','Шаблоны приёмов'],['meal-plan','▦','План питания']].map(([a,i,t])=>`<button class="choice tool-tile" data-action="${a}"><span class="feature-icon" aria-hidden="true">${i}</span><strong>${t}</strong></button>`).join('')}</div></div>`;}
function richAction(a,b){
 if(a==='tools'){go('tools');return true;}
 if(a==='rich-date'&&calendarDay(b.dataset.date)!==null&&b.dataset.date<=TODAY){state.date=b.dataset.date;go('home');return true;}
 return false;
}
